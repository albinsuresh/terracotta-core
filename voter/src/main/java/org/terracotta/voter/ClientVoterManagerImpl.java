package org.terracotta.voter;

import com.terracotta.connection.api.DiagnosticConnectionService;
import com.terracotta.diagnostic.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.connection.Connection;
import org.terracotta.connection.ConnectionException;
import org.terracotta.connection.entity.EntityRef;
import org.terracotta.exception.EntityNotFoundException;
import org.terracotta.exception.EntityNotProvidedException;
import org.terracotta.exception.EntityVersionMismatchException;

import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

public class ClientVoterManagerImpl implements ClientVoterManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClientVoterManagerImpl.class);

  public static final String VOTER_MANAGER_MBEAN = "VoterManager";
  public static final String REQUEST_TIMEOUT = "Request Timeout";

 private static final DiagnosticConnectionService CONNECTION_SERVICE = new DiagnosticConnectionService();

  private Connection connection;
  Diagnostics diagnostics;

  @Override
  public void connect(String hostPort) {
    URI uri = URI.create("diagnostic://" + hostPort);
    Properties properties = new Properties();
    try {
      connection = CONNECTION_SERVICE.connect(uri, properties);
      EntityRef<Diagnostics, Object, Properties> ref = connection.getEntityRef(Diagnostics.class, 1L, "root");;
      this.diagnostics = ref.fetchEntity(properties);
    } catch (ConnectionException | EntityNotProvidedException | EntityNotFoundException | EntityVersionMismatchException e) {
      throw new RuntimeException("Unable to connect to " + hostPort, e);
    }
  }

  @Override
  public long registerVoter(String id) {
    String result = null;
    try {
      result = processInvocation(diagnostics.invokeWithArg(VOTER_MANAGER_MBEAN, "registerVoter", id));
      return Long.parseLong(result);
    } catch (TimeoutException e) {
      return TIMEOUT_RESPONSE;
    }
  }

  @Override
  public long heartbeat(String id) {
    try {
      String result = processInvocation(diagnostics.invokeWithArg(VOTER_MANAGER_MBEAN, "heartbeat", id));
      return Long.parseLong(result);
    } catch (TimeoutException e) {
      return TIMEOUT_RESPONSE;
    }
  }

  @Override
  public long vote(String id, long term) {
    try {
      String result = processInvocation(diagnostics.invokeWithArg(VOTER_MANAGER_MBEAN, "vote", id + ":" + term));
      return Long.parseLong(result);
    } catch (TimeoutException e) {
      return TIMEOUT_RESPONSE;
    }
  }

  @Override
  public boolean vetoVote(String id) {
    String result = diagnostics.invokeWithArg(VOTER_MANAGER_MBEAN, "vetoVote", id);
    return Boolean.parseBoolean(result);
  }

  @Override
  public boolean deregisterVoter(String id) {
    String result = diagnostics.invokeWithArg(VOTER_MANAGER_MBEAN, "deregisterVoter", id);
    return Boolean.parseBoolean(result);
  }

  @Override
  public String getServerState() {
    return diagnostics.getState();
  }

  String processInvocation(String invocation) throws TimeoutException {
    if (invocation == REQUEST_TIMEOUT) {
      throw new TimeoutException("Request timed out");
    }
    return invocation;
  }

  @Override
  public void close() {
    try {
      this.connection.close();
    } catch (IOException e) {
      LOGGER.error("Failed to close the connection: {}", connection);
    }
  }

}
