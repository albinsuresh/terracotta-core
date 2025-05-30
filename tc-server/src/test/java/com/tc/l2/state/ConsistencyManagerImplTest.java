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
package com.tc.l2.state;

import com.tc.net.NodeID;
import com.tc.objectserver.impl.JMXSubsystem;
import com.tc.objectserver.impl.TopologyManager;
import com.tc.util.Assert;
import org.terracotta.configuration.FailoverBehavior;

import java.util.HashSet;
import java.util.UUID;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static java.util.Arrays.asList;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.terracotta.server.Server;
import org.terracotta.server.ServerEnv;
import org.terracotta.server.ServerJMX;

/**
 *
 */
public class ConsistencyManagerImplTest {

  public ConsistencyManagerImplTest() {
  }
  
  @BeforeClass
  public static void setUpClass() {
  }
  
  @AfterClass
  public static void tearDownClass() {
  }
  
  @Before
  public void setUp() {
  }
  
  @After
  public void tearDown() {
  }

  /**
   * Test of requestTransition method, of class ConsistencyManagerImpl.
   */
  @Test
  public void testVoteThreshold() throws Exception {
    String voter = UUID.randomUUID().toString();
    MBeanServer mServer = MBeanServerFactory.newMBeanServer();
    JMXSubsystem caller = new JMXSubsystem(mServer);
    Server server = mock(Server.class);
    ServerJMX jmx = mock(ServerJMX.class);
    when(jmx.getMBeanServer()).thenReturn(mServer);
    when(server.getManagement()).thenReturn(jmx);
    ServerEnv.setServer(server);
    TopologyManager topologyManager = new TopologyManager(()->new HashSet<>(asList("localhost:9410", "localhost:9510")), ()->1);
    ConsistencyManagerImpl impl = new ConsistencyManagerImpl(()->ServerMode.ACTIVE, topologyManager);
    caller.call(ServerVoterManager.MBEAN_NAME, "registerVoter", voter);
    long term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "heartbeat", voter));
    Assert.assertTrue(term == 0);
    new Thread(()->{
      long response = 0;
      while (response >= 0) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          throw new RuntimeException(ie);
        }
        response = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "heartbeat", voter));
      }
    }).start();
    boolean allowed = impl.requestTransition(ServerMode.PASSIVE, mock(NodeID.class), ConsistencyManager.Transition.MOVE_TO_ACTIVE);
    term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "heartbeat", voter));
    Assert.assertTrue(term > 0);
    Assert.assertFalse(allowed);
    term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "vote", voter + ":" + term));
    Assert.assertTrue(term == 0);
    term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "registerVoter", UUID.randomUUID().toString()));
    Assert.assertTrue(term < 0);
    allowed = impl.requestTransition(ServerMode.PASSIVE, mock(NodeID.class), ConsistencyManager.Transition.MOVE_TO_ACTIVE);
    Assert.assertTrue(allowed);
    Assert.assertTrue(Boolean.parseBoolean(caller.call(ServerVoterManager.MBEAN_NAME, "deregisterVoter", voter)));
  }

  @Test
  public void testVoteConfig() throws Exception {
    int servers = 1;
    
    Assert.assertEquals(-1, ConsistencyManager.parseVoteCount(new FailoverBehavior(FailoverBehavior.Type.AVAILABILITY, 0), servers));
    servers = 2;
    
    Assert.assertEquals(1, ConsistencyManager.parseVoteCount(new FailoverBehavior(FailoverBehavior.Type.CONSISTENCY, 1), servers));    
    Assert.assertEquals(2, ConsistencyManager.parseVoteCount(new FailoverBehavior(FailoverBehavior.Type.CONSISTENCY, 2), servers));
  }
  
  @Test
  public void testAddClientIsNotPersistent() throws Exception {
    TopologyManager topologyManager = new TopologyManager(()->new HashSet<>(asList("localhost:9410", "localhost:9510")), ()->1);
    ConsistencyManagerImpl impl = new ConsistencyManagerImpl(()->ServerMode.ACTIVE, topologyManager);
    long cterm = impl.getCurrentTerm();
    boolean granted = impl.requestTransition(ServerMode.ACTIVE, mock(NodeID.class), ConsistencyManager.Transition.ADD_CLIENT);
    Assert.assertTrue(granted);
    Assert.assertFalse(impl.isVoting());
    Assert.assertFalse(impl.isBlocked());
    Assert.assertEquals(cterm, impl.getCurrentTerm());
  }

  @Test
  public void testAddClientDoesntVote() throws Exception {
    TopologyManager topologyManager = new TopologyManager(()->new HashSet<>(asList("localhost:9410", "localhost:9510")), ()->1);
    ConsistencyManagerImpl impl = new ConsistencyManagerImpl(()->ServerMode.ACTIVE, topologyManager);
    long cterm = impl.getCurrentTerm();
    boolean granted = impl.requestTransition(ServerMode.ACTIVE, mock(NodeID.class), ConsistencyManager.Transition.ADD_CLIENT);
    Assert.assertTrue(granted);
    Assert.assertFalse(impl.isVoting());
    Assert.assertFalse(impl.isBlocked());
    Assert.assertEquals(cterm, impl.getCurrentTerm());
    granted = impl.requestTransition(ServerMode.ACTIVE, mock(NodeID.class), ConsistencyManager.Transition.REMOVE_PASSIVE);
    Assert.assertFalse(granted);
    Assert.assertTrue(impl.isVoting());
    Assert.assertTrue(impl.isBlocked());
    granted = impl.requestTransition(ServerMode.ACTIVE, mock(NodeID.class), ConsistencyManager.Transition.ADD_CLIENT);
    Assert.assertFalse(granted);
  }

  @Test
  public void testVoteConfigMandatoryForMultiNode() throws Exception {
    int servers = 2;
    Server server = mock(Server.class);
    ServerEnv.setServer(server);
    int count = ConsistencyManager.parseVoteCount(null, servers);
    verify(server).stop();
  }

  @Test
  public void testVoteConfigNotMandatoryForSingleNode() throws Exception {
    int servers = 1;
    Assert.assertEquals(-1, ConsistencyManager.parseVoteCount(new FailoverBehavior(FailoverBehavior.Type.CONSISTENCY, 1), servers));
  }

}
