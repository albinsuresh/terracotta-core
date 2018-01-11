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
package org.terracotta.voter;

import ch.qos.logback.classic.Level;
import com.tc.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

//import static com.tc.voter.VoterManager.TIMEOUT_RESPONSE;
import static com.tc.voter.VoterManager.HEARTBEAT_RESPONSE;
import static com.tc.voter.VoterManager.INVALID_VOTER_RESPONSE;
import static java.util.stream.Collectors.toList;

public class TCVoterImpl implements TCVoter {

  public final String ACTIVE_COORDINATOR   = "ACTIVE-COORDINATOR";

  private static final Logger LOGGER = LoggerFactory.getLogger(TCVoterImpl.class);

  private static final long CONNECTION_TIMEOUT = 5000L;
  private static final long HEARTBEAT_INTERVAL = 1000L;
  private static final long REG_RETRY_INTERVAL = 10000L;

  private volatile ExecutorService executorService;

  @Override
  public boolean vetoVote(String hostPort) {
    ClientVoterManager voterManager = new ClientVoterManagerImpl();
    voterManager.connect(hostPort);
    String id = UUID.getUUID().toString();
    boolean veto = voterManager.vetoVote(id);
    if (veto) {
      LOGGER.info("Successfully cast a veto vote to {}", hostPort);
    } else {
      LOGGER.info("Veto vote rejected by {}", hostPort);
    }
    return veto;
  }

  @Override
  public void register(String clusterName, String... hostPorts) {
    executorService = Executors.newFixedThreadPool(hostPorts.length);
    String id = UUID.getUUID().toString();
    LOGGER.info("Voter ID generated: {}", id);

    //TODO Rewrite the following code with CompletableFutures if possible

    while (true) {
      AtomicLong currentTerm = new AtomicLong(-1);
      List<CompletableFuture<?>> regTasks = Stream.of(hostPorts).map(hostPort -> CompletableFuture.runAsync(() -> {
        ClientVoterManager voterManager = new ClientVoterManagerImpl();
        while (currentTerm.get() < 0) {
          voterManager.connect(hostPort);
          String serverState = voterManager.getServerState();
          if (serverState.equals(ACTIVE_COORDINATOR)) {
            currentTerm.set(voterManager.registerVoter(id));
            if (currentTerm.get() >= 0) {
              voterManager.close();
              return;
            }
          }
          voterManager.close();
          try {
            Thread.sleep(REG_RETRY_INTERVAL);
          } catch (InterruptedException e) {
            throw new RuntimeException("Voter registration interrupted", e);
          }
        }
      })).collect(toList());

      try {
        CompletableFuture.anyOf(regTasks.toArray(new CompletableFuture[0])).get();
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException("Voter registration could not be completed due to: ", e);
      }


      //Try to connect and register with all the servers
      List<CompletableFuture<?>> voterTasks = Stream.of(hostPorts).map(hostPort -> CompletableFuture.runAsync(() -> {
        try {
          LOGGER.trace("Voter connecting to {}", hostPort);
          ClientVoterManager voterManager = new ClientVoterManagerImpl();
          voterManager.connect(hostPort);
          LOGGER.info("Voter connected to {}", hostPort);
          while (voterManager.registerVoter(id) < 0) {
            Thread.sleep(REG_RETRY_INTERVAL);
            LOGGER.info("Retrying voter registration");
          }
          LOGGER.info("Voter registered with {}", hostPort);

          long beatResponse = 0;
          while (true) {
            if (beatResponse == HEARTBEAT_RESPONSE) {
              beatResponse = voterManager.heartbeat(id);
              Thread.sleep(HEARTBEAT_INTERVAL);
            } else if (beatResponse > 0) {  //Election term number. This is the election request from the server
              long term = beatResponse;
              long current = currentTerm.get();
              if (term > current && currentTerm.compareAndSet(current, term)) {
                //Make sure that you vote only once for one term
                beatResponse = voterManager.vote(id, term);
              } else {
                beatResponse = voterManager.heartbeat(id);
              }
              Thread.sleep(HEARTBEAT_INTERVAL);
            } else if (beatResponse == INVALID_VOTER_RESPONSE) {
              LOGGER.info("Server rejected this voter as invalid. Attempting to re-register with the cluster.");
              break;
//            } else if (beatResponse == TIMEOUT_RESPONSE) {
//              LOGGER.info("Request timed-out. Attempting to reconnect to: {}", hostPort);
//              voterManager.close();
//              voterManager.connect(hostPort);
//              LOGGER.info("Reconnected to: {}", hostPort);
//              beatResponse = voterManager.registerVoter(id);
//              if (beatResponse > 0) {
//                beatResponse = voterManager.heartbeat(id);
//              }
            } else {
              LOGGER.error("Unexpected response received: {}", beatResponse);
              break;
            }
            LOGGER.info("Response {}", beatResponse);
          }
          voterManager.close();
        } catch (InterruptedException e) {
          //TODO
          e.printStackTrace();
        }
      })).collect(toList());

      try {
        CompletableFuture.allOf(voterTasks.toArray(new CompletableFuture[0])).get();
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException("Voter registration could not be completed due to: ", e);
      }

      LOGGER.warn("Heartbeats to all servers stopped. Attempting to re-register");
    }
  }

  @Override
  public void deregister(String clusterName) {
    executorService.shutdownNow();
  }

  public static void main(String[] args) {
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("root");
    logger.setLevel(Level.INFO);
    TCVoter voter = new TCVoterImpl();
    voter.vetoVote("localhost:9410");
//    voter.register("foo", "localhost:9410", "localhost:9510");
  }

}
