package org.terracotta.voter;

import com.tc.voter.VoterManager;

public interface ClientVoterManager extends VoterManager {

  long TIMEOUT_RESPONSE = Long.MIN_VALUE;

  /**
   * Establish a connection with the server at the given host and port
   * @param hostPort host and port of the server separated by a ":"
   */
  void connect(String hostPort);

  /**
   *
   * @return the current state of the server that this voter is connected to.
   */
  String getServerState();

  /**
   * Close the connection with the server.
   */
  void close();
}
