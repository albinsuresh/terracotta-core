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
package com.tc.l2.ha;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tc.net.StripeID;
import com.tc.net.groups.StripeIDStateManager;
import com.tc.net.protocol.transport.ConnectionID;
import com.tc.net.protocol.transport.ConnectionIDFactory;
import com.tc.objectserver.api.ClientNotFoundException;
import com.tc.objectserver.persistence.Persistor;
import com.tc.util.State;
import com.tc.util.UUID;

import java.util.ArrayList;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClusterStateImpl implements ClusterState {

  private static final Logger logger = LoggerFactory.getLogger(ClusterState.class);

  private final Persistor               persistor;
  private final ConnectionIDFactory                 connectionIdFactory;
  private final StripeIDStateManager                stripeIDStateManager;

  private final Set<ConnectionID>                   connections            = Collections.synchronizedSet(new HashSet<>());
  private long                                      nextAvailChannelID     = -1;
  private volatile long                                      globalMessageID = -1;
  private volatile State                                     currentState;
  private byte[]                                    configSyncData = new byte[0];

  public ClusterStateImpl(Persistor persistor, 
                          ConnectionIDFactory connectionIdFactory, StripeIDStateManager stripeIDStateManager) {
    this.persistor = persistor;
    this.connectionIdFactory = connectionIdFactory;
    this.stripeIDStateManager = stripeIDStateManager;
    this.nextAvailChannelID = this.connectionIdFactory.getCurrentConnectionID();
  }

  @Override
  public synchronized long getNextAvailableChannelID() {
    return nextAvailChannelID;
  }

  @Override
  public long getStartGlobalMessageID() {
    return globalMessageID;
  }

  @Override
  public void setStartGlobalMessageID(long id) {
    globalMessageID = id;
  }

  @Override
  public synchronized void setNextAvailableChannelID(long nextAvailableCID) {
    if (nextAvailableCID < nextAvailChannelID) {
      // Could happen when two actives fight it out. Dont want to assert, let the state manager fight it out.
      logger.error("Trying to set Next Available ChannelID to a lesser value : known = " + nextAvailChannelID
                   + " new value = " + nextAvailableCID + " IGNORING");
      return;
    }
    logger.info("setting next available cid: {}", nextAvailableCID);
    this.nextAvailChannelID = nextAvailableCID;
    persistor.getClientStatePersistor().getConnectionIDSequence().setNext(nextAvailChannelID);
  }

  @Override
  public void syncActiveState() {
// activate the connection id factory so that it can be used to create connection ids
// this happens for active only
// when going active, start the next available ID+10 so that on restarts with persistent state, 
// this active is picked via the additional election weightings
    long nextId = getNextAvailableChannelID() + 1;
    setNextAvailableChannelID(nextId);
    connectionIdFactory.activate(stripeIDStateManager.getStripeID(), nextId);
  }

  @Override
  public void syncSequenceState() {
  }

  @Override
  public StripeID getStripeID() {
    return stripeIDStateManager.getStripeID();
  }

  private boolean isStripeIDNull() {
    return stripeIDStateManager.getStripeID().isNull();
  }

  @Override
  public void setStripeID(String uid) {
    if (!isStripeIDNull() && !stripeIDStateManager.getStripeID().getName().equals(uid)) {
      logger.error("StripeID doesnt match !! Mine : " + stripeIDStateManager.getStripeID() + " Active sent clusterID as : " + uid);
      throw new ClusterIDMissmatchException(stripeIDStateManager.getStripeID().getName(), uid);
    }

    // notify stripeIDStateManager
    stripeIDStateManager.verifyOrSaveStripeID(new StripeID(uid), true);
  }

  @Override
  public void setCurrentState(State state) {
    this.currentState = state;
    syncCurrentStateToDB();
  }

  private void syncCurrentStateToDB() {
    persistor.getClusterStatePersistor().setCurrentL2State(currentState);
  }

  @Override
  public void addNewConnection(ConnectionID connID) {
    if (connID.getChannelID() >= getNextAvailableChannelID()) {
      long cid = connID.getChannelID() + 1;
      setNextAvailableChannelID(cid);
    }
    connections.add(connID);
    logger.info("connection added {}", connID);
    if (connID.getProductId().isReconnectEnabled()) {
      persistor.addClientState(connID.getClientID(), connID.getProductId());
    }
  }

  @Override
  public void removeConnection(ConnectionID connectionID) {
    boolean removed = connections.remove(connectionID);
    if (!removed) {
      logger.debug("Connection ID not found, must be a failed reconnect : " + connectionID + " Current Connections count : " + connections.size());
    } else {
      logger.info("connection removed {}", connectionID);
    }
    try {
      if (connectionID.getProductId().isReconnectEnabled()) {
        persistor.removeClientState(connectionID.getClientID());
      }
    } catch (ClientNotFoundException notfound) {
      logger.debug("not found", notfound);
    }
  }

  @Override
  public Set<ConnectionID> getAllConnections() {
    return new HashSet<>(connections);
  }

  @Override
  public void generateStripeIDIfNeeded() {
    if (isStripeIDNull()) {
      // This is the first time an L2 goes active in the cluster of L2s. Generate a new stripeID. this will stick.
      setStripeID(UUID.getUUID().toString());
    }
  }
  
  @Override
  public String toString() {
    StringBuilder strBuilder = new StringBuilder();
    strBuilder.append("ClusterState [ ");
    strBuilder.append("Connections [ ").append(this.connections).append(" ]");
    strBuilder.append(" nextAvailChannelID: ").append(this.nextAvailChannelID);
    strBuilder.append(" currentState: ").append(this.currentState);
    strBuilder.append(" stripeID: ").append(stripeIDStateManager.getStripeID());
    strBuilder.append(" ]");
    return strBuilder.toString();
  }

  @Override
  public void reportStateToMap(Map<String, Object> state) {
    List<String> connects = new ArrayList<>(this.connections.size());
    this.connections.forEach((c)->connects.add(c.toString()));
    state.put("connections", getAllConnections());
    state.put("nextChannelID", getNextAvailableChannelID());
    state.put("currentState", this.currentState);
    state.put("stripeID", stripeIDStateManager.getStripeID());
  }

  @Override
  public byte[] getConfigSyncData() {
    return configSyncData;
  }

  @Override
  public void setConfigSyncData(byte[] configSyncData) {
    this.configSyncData = configSyncData;
  }
}
