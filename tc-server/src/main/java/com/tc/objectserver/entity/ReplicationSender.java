/*
 *  Copyright Terracotta, Inc.
 *  Copyright IBM Corp. 2024, 2025
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tc.objectserver.entity;

import com.tc.async.api.Sink;
import com.tc.l2.msg.ReplicationMessage;
import com.tc.l2.msg.SyncReplicationActivity;
import com.tc.net.ServerID;
import com.tc.net.groups.AbstractGroupMessage;
import com.tc.net.groups.GroupException;
import com.tc.net.groups.GroupManager;
import com.tc.object.FetchID;
import com.tc.object.session.SessionID;
import com.tc.objectserver.handler.GroupMessageBatchContext;
import com.tc.objectserver.handler.ReplicationSendingAction;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.entity.ConcurrencyStrategy;
import org.terracotta.tripwire.Event;
import org.terracotta.tripwire.TripwireFactory;


public class ReplicationSender {
  private static final int DEFAULT_BATCH_LIMIT = 1024;
  private static final int DEFAULT_INFLIGHT_MESSAGES = 1;
  // Find out how many messages we should keep in-flight and our maximum batch size.
  private static int maximumBatchSize = TCPropertiesImpl.getProperties().getInt("active-passive.batchsize", DEFAULT_BATCH_LIMIT);
  private static int idealMessagesInFlight = TCPropertiesImpl.getProperties().getInt("active-passive.inflight", DEFAULT_INFLIGHT_MESSAGES);
  //  this is all single threaded.  If there is any attempt to make this multi-threaded,
  //  control structures must be fixed
  private final GroupManager<AbstractGroupMessage> group;
  private final Map<SessionID, SyncState> filtering = new ConcurrentHashMap<>();
  private static final Logger logger = LoggerFactory.getLogger(ReplicationSender.class);
  private static final Logger PLOGGER = LoggerFactory.getLogger(MessagePayload.class);
  private static final boolean debugLogging = logger.isDebugEnabled();
  private static final boolean debugMessaging = PLOGGER.isDebugEnabled();

  private final Sink<ReplicationSendingAction> outgoing;
  private final Sink<ReplicationSendingAction> flush;

  public ReplicationSender(Sink<ReplicationSendingAction> outgoing, Sink<ReplicationSendingAction> flush, GroupManager<AbstractGroupMessage> group) {
    this.group = group;
    this.outgoing = outgoing;
    this.flush = flush;
  }

  public void removePassive(SessionID dest) {
    filtering.remove(dest);
  }

  public boolean addPassive(ServerID node, SessionID session, Integer execution, SyncReplicationActivity activity) {
    // Set up the sync state.
    Event event = TripwireFactory.createPrimeEvent(node.getName(), node.getUID(), session.toLong(), activity.getSequenceID());
    SyncState state = createAndRegisterSyncState(node, session, execution);
    // Send the message.
    event.commit();
    return state.attemptToSend(activity);
  }

  public void replicateMessage(SessionID session, SyncReplicationActivity activity, Consumer<Boolean> sentCallback) {
    if (debugLogging) {
      logger.debug("WIRE:" + activity);
    }
    if (debugMessaging) {
      PLOGGER.debug("SENDING:" + activity.getDebugID());
    }
    Optional<SyncState> syncing = getSyncState(session, activity);
    if (syncing.isPresent()) {
      outgoing.addToSink(new ReplicationSendingAction(syncing.get().executionLane, ()->{
            Optional<Boolean> didSend = syncing.map(state->state.attemptToSend(activity));
            if (sentCallback != null) {
              sentCallback.accept(didSend.orElse(false));
            }
      }));
    } else {
      logger.info("ignoring replication message no session {} for activity {}", session, activity);
      if (sentCallback != null) {
        sentCallback.accept(false);
      }
    }

  }
  
  private SyncState createAndRegisterSyncState(ServerID node, SessionID session, int lane) {
    // We can't already have a state for this passive.
    Assert.assertTrue(!node.isNull());
    Assert.assertTrue(!filtering.containsKey(session));
    SyncState state = new SyncState(node, session, lane);
    filtering.put(session, state);
    return state;
  }

  private Optional<SyncState> getSyncState(SessionID session, SyncReplicationActivity activity) {
    SyncState state = filtering.get(session);
    if (null == state || !state.isSameSession(session)) {
      // We don't know anything about this passive so drop the message.
      dropActivityForDisconnectedServer(session, activity);
      return Optional.empty();
    } else {
      return Optional.of(state);
    }
  }

  private void dropActivityForDisconnectedServer(SessionID session, SyncReplicationActivity activity) {
//  passive must have died during passive sync, ignore this message
    if (logger.isDebugEnabled()) {
      logger.debug("ignoring: " + session + " no longer exists");
    }
  }
// for testing only
  boolean isSyncOccuring(SessionID origin) {
    SyncState state = filtering.get(origin);
    if (state != null) {
      return state.isSyncOccuring();
    }
    return false;
  }  
  
  private class SyncState {
    // liveSet is the total set of entities which we believe have finished syncing and fully exist on the passive.
    private final Set<FetchID> liveFetch = new HashSet<>();
    // syncdID is the set of concurrency keys, of the entity syncingID, which we believe have finished syncing and fully
    //  exist on the passive.
    private final Set<Integer> syncdID = new HashSet<>();
    // syncingID is the entity currently being synced to this passive.
    private FetchID syncingFetch = FetchID.NULL_ID;
    // syncingConcurrency is the concurrency key we are currently syncing to syncingID, 0 if none is in progress.
    private int syncingConcurrency = -1;
    // begun is true when we decide to start syncing to this passive node (triggered by SYNC_BEGIN).
    boolean begun = false;
    // complete is true when we decide that syncing to this node is now complete (triggered by SYNC_END).
    boolean complete = false;
    private SyncReplicationActivity.ActivityType lastSeen;
    private SyncReplicationActivity.ActivityType lastSent;

    private final GroupMessageBatchContext<ReplicationMessage, SyncReplicationActivity> batchContext;
    
    private final SessionID session;
    private final int executionLane;
        
    public SyncState(ServerID target, SessionID nodeToId, int lane) {
      this.session = nodeToId;
      this.executionLane = lane;
      
      this.batchContext = new GroupMessageBatchContext<>(ReplicationMessage::createActivityContainer, group, target, maximumBatchSize, idealMessagesInFlight, (node)->flushBatch());  
    }
    
    private boolean isSameSession(SessionID session) {
      return this.session.equals(session);
    }
              
    public boolean isSyncOccuring() {
      return (begun && !complete);
    }
    
    public boolean hasSyncBegun() {
      return begun;
    }
    
    public boolean hasSyncFinished() {
      return complete;
    }
    
    public boolean attemptToSend(SyncReplicationActivity activity) {
      boolean shouldRemoveFromStream = !(hasSyncFinished() 
              || shouldMessageBeReplicated(activity)
              || !hasSyncBegun());
      if (!shouldRemoveFromStream) {
      // We want to send this message.
        validateSending(activity);

        if (debugLogging && activity.getActivityType() != SyncReplicationActivity.ActivityType.SYNC_BEGIN) {
          logger.debug("SENDING:" + activity.getActivityType() +" " +  activity.getEntityID() + " " + activity.getFetchID() + " " + activity.getSource() + " " + activity.getClientInstanceID() + " " + activity.getActivityID().id);
        }
        
        send(activity);
        
        return true;
      } else {
        if (debugLogging) {
          logger.debug("FILTERING:" + activity);
        }  
        return false;
      }
    }
    
    private boolean shouldMessageBeReplicated(SyncReplicationActivity activity) {
        switch (validateInput(activity)) {
          case SYNC_BEGIN:
            begun = true;
            return true;
          case SYNC_ENTITY_BEGIN:
            if (liveFetch.contains(activity.getFetchID())) {
              return false;
            } else {
              syncingFetch = activity.getFetchID();
              syncdID.clear();
              syncdID.add(ConcurrencyStrategy.MANAGEMENT_KEY);
              syncdID.add(ConcurrencyStrategy.UNIVERSAL_KEY);
              syncingConcurrency = 0;
              return true;
            }
          case SYNC_ENTITY_CONCURRENCY_BEGIN:
            if (syncingFetch.equals(activity.getFetchID())) {
              Assert.assertEquals(syncingConcurrency, 0);
              syncingConcurrency = activity.getConcurrency();
              return true;
            } else {
              return false;
            }
          case SYNC_ENTITY_CONCURRENCY_PAYLOAD:
            if (syncingFetch.equals(activity.getFetchID())) {
              return true;
            } else {
              return false;
            }
          case SYNC_ENTITY_CONCURRENCY_END:
            if (syncingFetch.equals(activity.getFetchID())) {
              syncdID.add(syncingConcurrency);
              syncingConcurrency = 0;
              return true;
            } else {
              return false;
            }
          case SYNC_ENTITY_END:
            if (syncingFetch.equals(activity.getFetchID())) {
              liveFetch.add(syncingFetch);
              syncingFetch = FetchID.NULL_ID;
              return true;
            } else {
              return false;
            }
          case SYNC_END:
 //  sync is complete, clear all collections and let everything pass
            complete = true;
            liveFetch.clear();
            syncdID.clear();
            syncingFetch = FetchID.NULL_ID;
            return true;
          case CREATE_ENTITY:
// if this create came through, it is not part of the snapshot set so everything
// applies
            if (begun) {
              liveFetch.add(activity.getFetchID());
            }
//  fall-through
          case RECONFIGURE_ENTITY:
          case FETCH_ENTITY:
          case RELEASE_ENTITY:
          case DISCONNECT_CLIENT:
          case DESTROY_ENTITY:
            return begun;
          case INVOKE_ACTION:
            if (liveFetch.contains(activity.getFetchID())) {
              return true;
            } else if (syncingFetch.equals(activity.getFetchID())) {
              int concurrencyKey = activity.getConcurrency();
              if (syncingConcurrency == concurrencyKey) {
//  special case.  passive will apply this after sync of the key is complete
                return true;
              }
              return syncdID.contains(concurrencyKey);
            } else {
// hasn't been sync'd yet.  state will be captured in sync
              return false;
            }
          case LOCAL_ENTITY_GC:
          case FLUSH_LOCAL_PIPELINE:
          case ORDERING_PLACEHOLDER:
            // TODO: Should we handle this placeholder a different way - excluding it at this level seems counter-intuitive.
            return false;
          case SYNC_START:
            return true;
            // SYNC_START shouldn't go down this path - it is handled, explicitly, at a higher level.
          default:
            throw new AssertionError("unknown replication activity:" + activity);
        }
    }

    public SyncReplicationActivity.ActivityType validateInput(SyncReplicationActivity activity) {
      SyncReplicationActivity.ActivityType type = activity.getActivityType();
      if (activity.isSyncActivity()) {
        lastSeen = validate(type, lastSeen);
      }
      return type;
    }
    
    public void validateSending(SyncReplicationActivity activity) {
      if (activity.isSyncActivity()) {
        lastSent = validate(activity.getActivityType(), lastSent);
      }
    }
    
    private SyncReplicationActivity.ActivityType validate(SyncReplicationActivity.ActivityType type, SyncReplicationActivity.ActivityType compare) {
      switch (type) {
        case SYNC_BEGIN:
          Assert.assertTrue(type + " " + compare, EnumSet.of(SyncReplicationActivity.ActivityType.SYNC_START).contains(compare));
          break;
        case SYNC_ENTITY_BEGIN:
          Assert.assertTrue(type + " " + compare, EnumSet.of(SyncReplicationActivity.ActivityType.SYNC_BEGIN, SyncReplicationActivity.ActivityType.SYNC_ENTITY_END).contains(compare));
          break;
        case SYNC_ENTITY_CONCURRENCY_BEGIN:
          Assert.assertTrue(type + " " + compare, EnumSet.of(SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_PAYLOAD, SyncReplicationActivity.ActivityType.SYNC_ENTITY_BEGIN, SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_END).contains(compare));
          break;
        case SYNC_ENTITY_CONCURRENCY_PAYLOAD:
          Assert.assertTrue(type + " " + compare, EnumSet.of(SyncReplicationActivity.ActivityType.SYNC_ENTITY_BEGIN, SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_BEGIN, SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_END, SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_PAYLOAD).contains(compare));
          break;
        case SYNC_ENTITY_CONCURRENCY_END:
          Assert.assertTrue(type + " " + compare, EnumSet.of(SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_BEGIN, SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_PAYLOAD).contains(compare));
          break;
        case SYNC_ENTITY_END:
          Assert.assertTrue(type + " " + compare, EnumSet.of(SyncReplicationActivity.ActivityType.SYNC_ENTITY_BEGIN, SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_END).contains(compare));
          break;
        case SYNC_END:
          Assert.assertTrue(type + " " + compare, EnumSet.of(SyncReplicationActivity.ActivityType.SYNC_ENTITY_END, SyncReplicationActivity.ActivityType.SYNC_BEGIN).contains(compare));
          break;
        case SYNC_START:
          break;
          // SYNC_START shouldn't go down this path - it is handled, explicitly, at a higher level.
        default:
          throw new AssertionError("unexpected message type");
      }
      return type;
    }
    
    private boolean send(SyncReplicationActivity activity) {
      if (this.batchContext.batchMessage(activity)) {
        flushBatch();
      }
      return true;
    }
        
    private void flushBatch() {
      flush.addToSink(new ReplicationSendingAction(this.executionLane, ()->{
        try {
          this.batchContext.flushBatch();
        } catch (GroupException ge) {
          logger.warn("error sending message to passive ", ge);
        }
      }));
    }
  }
}
