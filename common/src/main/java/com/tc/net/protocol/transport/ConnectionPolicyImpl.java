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
package com.tc.net.protocol.transport;


import com.tc.util.Assert;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Enforces max connections (licenses) based on using one license per unique JVM.
 */
public class ConnectionPolicyImpl implements ConnectionPolicy {

  private final HashMap<String, HashSet<ConnectionID>> clientsByJvm = new HashMap<String, HashSet<ConnectionID>>();
  private final int                                    maxConnections;
  private int                                          maxReached;

  public ConnectionPolicyImpl(int maxConnections) {
    Assert.assertTrue("negative maxConnections", maxConnections >= 0);
    this.maxConnections = maxConnections;
  }

  @Override
  public synchronized boolean isConnectAllowed(ConnectionID connID) {
    if (connID.getProductId().isInternal() || !connID.isValid()) {
      // Don't count internal clients.
      return true;
    }

    HashSet<ConnectionID> jvmClients = clientsByJvm.get(connID.getJvmID());

    if (jvmClients == null && isMaxConnectionsReached()) {
      return false;
    }

    return true;
  }

  @Override
  public synchronized String toString() {
    return "ConnectionPolicy[maxConnections=" + maxConnections + ", connectedJvmCount=" + clientsByJvm.size() + "]";
  }

  @Override
  public synchronized boolean connectClient(ConnectionID connID) {
    if (connID.getProductId().isInternal() || connID.getChannelID() < 0) {
      // Always allow connections from internal products
      return true;
    }

    HashSet<ConnectionID> jvmClients = clientsByJvm.get(connID.getJvmID());

    if (isMaxConnectionsReached() && jvmClients == null) {
      return false;
    }

    if (jvmClients == null) {
      jvmClients = new HashSet<ConnectionID>();
      clientsByJvm.put(connID.getJvmID(), jvmClients);
      maxReached = clientsByJvm.size();
    }

    if (!jvmClients.contains(connID)) {
      jvmClients.add(connID);
    }

    return true;
  }

  @Override
  public synchronized boolean clientDisconnected(ConnectionID connID) {
    if (connID.getProductId().isInternal() || !connID.isValid()) {
      // ignore internal clients
      return false;
    }

    // not all times clientSet has connID client disconnect removes the connID. after reconnect timeout, for close event
    // we get here again.

    HashSet<ConnectionID> jvmClients = clientsByJvm.get(connID.getJvmID());

    if (jvmClients == null) return false; // must have already received the event for this client

    boolean removed = jvmClients.remove(connID);

    if (removed && jvmClients.isEmpty()) {
      clientsByJvm.remove(connID.getJvmID());
    }

    return removed;
  }

  @Override
  public synchronized boolean isMaxConnectionsReached() {
    return (clientsByJvm.size() >= maxConnections);
  }

  @Override
  public synchronized int getMaxConnections() {
    return maxConnections;
  }

  @Override
  public synchronized int getNumberOfActiveConnections() {
    return clientsByJvm.size();
  }

  @Override
  public synchronized int getConnectionHighWatermark() {
    return maxReached;
  }
}
