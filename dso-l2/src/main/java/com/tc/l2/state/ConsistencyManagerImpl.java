/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.l2.state;

import com.tc.net.NodeID;
import com.tc.net.groups.GroupManager;
import com.tc.util.Assert;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsistencyManagerImpl implements ConsistencyManager {
  
  private static final Logger LOGGER = LoggerFactory.getLogger(ConsistencyManagerImpl.class);
  private final int peers;
  private final int thresholdPeers;
  private boolean activeVote = false;
  private long voteTerm = 0;
  private final ServerVoterManager voter;
  private final  Set<NodeID> activePeers = ConcurrentHashMap.newKeySet();
  
  public ConsistencyManagerImpl(GroupManager mgr, int knownPeers, int voters) {
    try {
      this.peers = knownPeers;
      this.thresholdPeers = (int)Math.floor(peers * .5) + 1;
      this.voter = new ServerVoterManagerImpl(voters);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean requestTransition(ServerMode mode, NodeID sourceNode, Transition newMode) throws IllegalStateException {
    if (newMode == Transition.ADD_PASSIVE) {
      activePeers.add(sourceNode);
      Assert.assertEquals(mode, ServerMode.ACTIVE);
      //  adding a passive to an active is always OK
      return true;
    }
    if (newMode == Transition.REMOVE_PASSIVE) {
      activePeers.remove(sourceNode);
      Assert.assertEquals(mode, ServerMode.ACTIVE);
    }
    if (activePeers.size() >= thresholdPeers) {
      return true;
    }
    activateVoting();
    long start = System.currentTimeMillis();
    boolean allow = false;
    try {
      if (voter.getRegisteredVoters() == 0 && !voter.vetoVoteReceived()) {
        LOGGER.warn("No registered voters.  Require veto intervention or all members of the stripe to be connected for operation " + newMode);
      } else while (!allow && System.currentTimeMillis() - start < ServerVoterManagerImpl.VOTEBEAT_TIMEOUT) {
        try {
          if (activePeers.size() < thresholdPeers && voter.getVoteCount() < voter.getRegisteredVoters()) {
            TimeUnit.MILLISECONDS.sleep(100);
          } else {
            allow = true;
          }
        } catch (InterruptedException ie) {
          break;
        }
      }
    } finally {
      if (allow) {
        endVoting();
      }    
    }
    return allow;
  }
  
  private synchronized void activateVoting() {
    if (!activeVote) {
      activeVote = true;
      voter.startVoting(++voteTerm);
    }
  }
  
  private synchronized void endVoting() {
    if (activeVote) {
      Assert.assertEquals(voteTerm, voter.endVoting());
      activeVote = false;
    }
  }
}
