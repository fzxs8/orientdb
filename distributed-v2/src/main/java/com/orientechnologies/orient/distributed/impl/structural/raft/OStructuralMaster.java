package com.orientechnologies.orient.distributed.impl.structural.raft;

import com.orientechnologies.orient.core.db.config.ONodeIdentity;
import com.orientechnologies.orient.distributed.OrientDBDistributed;
import com.orientechnologies.orient.distributed.impl.coordinator.ODistributedLockManager;
import com.orientechnologies.orient.distributed.impl.coordinator.OLogId;
import com.orientechnologies.orient.distributed.impl.coordinator.OOperationLog;
import com.orientechnologies.orient.distributed.impl.coordinator.OOperationLogEntry;
import com.orientechnologies.orient.distributed.impl.coordinator.lock.ODistributedLockManagerImpl;
import com.orientechnologies.orient.distributed.impl.coordinator.transaction.OSessionOperationId;
import com.orientechnologies.orient.distributed.impl.structural.OStructuralConfiguration;
import com.orientechnologies.orient.distributed.impl.structural.OStructuralDistributedMember;
import com.orientechnologies.orient.distributed.impl.structural.OStructuralSharedConfiguration;
import com.orientechnologies.orient.distributed.impl.structural.operations.OCreateDatabaseSubmitResponse;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class OStructuralMaster implements AutoCloseable, OMasterContext {
  private static final String                                           CONF_RESOURCE = "Configuration";
  private final        ExecutorService                                  executor;
  private final        OOperationLog                                    operationLog;
  private final        ConcurrentMap<OLogId, ORaftRequestContext>       contexts      = new ConcurrentHashMap<>();
  private final        Map<ONodeIdentity, OStructuralDistributedMember> members       = new ConcurrentHashMap<>();
  private final        Timer                                            timer;
  private final        OrientDBDistributed                              context;
  private              ODistributedLockManager                          lockManager   = new ODistributedLockManagerImpl();
  private              int                                              quorum;
  private              int                                              timeout;

  public OStructuralMaster(ExecutorService executor, OOperationLog operationLog, OrientDBDistributed context, int quorum,
      int timeout) {
    this.executor = executor;
    this.operationLog = operationLog;
    this.timer = new Timer(true);
    this.context = context;
    this.quorum = quorum;
    this.timeout = timeout;
  }

  public void propagateAndApply(ORaftOperation operation, OpFinished finished) {
    executor.execute(() -> {
      OLogId id = operationLog.log(operation);
      contexts.put(id, new ORaftRequestContext(operation, quorum, finished));
      timer.schedule(new ORaftOperationTimeoutTimerTask(this, id), timeout, timeout);
      for (OStructuralDistributedMember value : members.values()) {
        value.propagate(id, operation);
      }
    });
  }

  public void receiveAck(ONodeIdentity node, OLogId id) {
    executor.execute(() -> {
      ORaftRequestContext ctx = contexts.get(id);
      if (ctx != null && ctx.ack(node, this)) {
        for (OStructuralDistributedMember value : members.values()) {
          value.confirm(id);
        }
        contexts.remove(id);
      }
    });
  }

  public void operationTimeout(OLogId id, TimerTask tt) {
    executor.execute(() -> {
      ORaftRequestContext ctx = contexts.get(id);
      if (ctx != null) {
        if (ctx.timeout()) {
          contexts.remove(id);
          //TODO: if an operation timedout, is should stop everything following raft.
          tt.cancel();
        }
      } else {
        tt.cancel();
      }
    });
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      executor.awaitTermination(1, TimeUnit.HOURS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void receiveSubmit(ONodeIdentity senderNode, OSessionOperationId operationId, OStructuralSubmit request) {
    executor.execute(() -> {
      request.begin(Optional.of(senderNode), operationId, this);
    });
  }

  public void submit(OSessionOperationId operationId, OStructuralSubmit request) {
    executor.execute(() -> {
      request.begin(Optional.empty(), operationId, this);
    });
  }

  @Override
  public OrientDBDistributed getOrientDB() {
    return context;
  }

  public ODistributedLockManager getLockManager() {
    return lockManager;
  }

  public void join(ONodeIdentity identity) {
    submit(new OSessionOperationId(), (requester, id, context) -> {
      getLockManager().lockResource(CONF_RESOURCE, (guards) -> {
        OStructuralSharedConfiguration conf = context.getOrientDB().getStructuralConfiguration().getSharedConfiguration();
        if (!conf.existsNode(identity) && conf.canAddNode(identity)) {
          this.propagateAndApply(new ONodeJoin(identity), () -> {
            getLockManager().unlock(guards);
          });
        }
      });
    });
  }

  @Override
  public void createDatabase(Optional<ONodeIdentity> requester, OSessionOperationId operationId, String database, String type,
      Map<String, String> configurations) {
    getLockManager().lockResource(CONF_RESOURCE, (guards) -> {
      OStructuralSharedConfiguration shared = getOrientDB().getStructuralConfiguration().getSharedConfiguration();
      if (!shared.existsDatabase(database)) {
        this.propagateAndApply(new OCreateDatabase(operationId, database, type, configurations), () -> {
          getLockManager().unlock(guards);
          if (requester.isPresent()) {
            OStructuralDistributedMember requesterChannel = members.get(requester.get());
            requesterChannel.reply(operationId, new OCreateDatabaseSubmitResponse(true, ""));
          }
        });
      } else {
        if (requester.isPresent()) {
          OStructuralDistributedMember requesterChannel = members.get(requester.get());
          requesterChannel.reply(operationId, new OCreateDatabaseSubmitResponse(false, "Database Already Exists"));
        }
      }
    });
  }

  public void connected(OStructuralDistributedMember member) {
    members.put(member.getIdentity(), member);
  }

  @Override
  public void tryResend(ONodeIdentity identity, OLogId logId) {
    //TODO: this may fail, handle the failure.
    executor.execute(() -> {
      //TODO: this in single thread executor may cost too much, find a different implementation
      Iterator<OOperationLogEntry> iter = operationLog.iterate(logId, operationLog.lastPersistentLog());
      while (iter.hasNext()) {
        OOperationLogEntry logEntry = iter.next();
        members.get(identity).propagate(logEntry.getLogId(), (ORaftOperation) logEntry.getRequest());
      }
    });
  }

  @Override
  public void sendFullConfiguration(ONodeIdentity identity) {
    executor.execute(() -> {
      OStructuralConfiguration structuralConfiguration = getOrientDB().getStructuralConfiguration();
      OLogId lastId = structuralConfiguration.getLastUpdateId();
      OStructuralSharedConfiguration shared = structuralConfiguration.getSharedConfiguration();
      members.get(identity).send(new OFullConfiguration(lastId, shared));
    });
  }
}
