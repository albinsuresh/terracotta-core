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
package com.tc.object;

import com.tc.async.api.EventHandler;
import com.tc.async.api.EventHandlerException;
import com.tc.async.api.Sink;
import com.tc.bytes.TCByteBuffer;
import com.tc.entity.MessageCodecSupplier;
import org.junit.Assert;
import org.junit.Test;
import org.terracotta.entity.EntityClientEndpoint;
import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.EntityResponse;
import org.terracotta.entity.InvocationCallback;
import org.terracotta.entity.InvocationCallback.Types;
import org.terracotta.entity.MessageCodec;
import org.terracotta.entity.MessageCodecException;
import org.terracotta.exception.ConnectionClosedException;
import org.terracotta.exception.EntityException;
import org.terracotta.exception.EntityNotFoundException;

import com.tc.entity.NetworkVoltronEntityMessage;
import com.tc.entity.VoltronEntityMessage.Acks;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.net.protocol.tcm.ClientMessageChannel;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.net.protocol.tcm.UnknownNameException;
import com.tc.object.session.SessionID;
import com.tc.object.tx.TransactionID;
import com.tc.net.core.ProductID;
import com.tc.net.protocol.tcm.NetworkRecall;
import com.tc.util.concurrent.ThreadUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import junit.framework.TestCase;

import static java.util.EnumSet.allOf;
import static org.hamcrest.CoreMatchers.is;
import org.hamcrest.Matchers;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import com.tc.net.protocol.tcm.TCAction;


public class ClientEntityManagerTest extends TestCase {
  private ClientMessageChannel channel;
  private ClientEntityManager manager;
  
  private EntityID entityID;
  private ClientInstanceID instance;
  private EntityDescriptor descriptor;

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Override
  public void setUp() throws Exception {
    this.channel = mock(ClientMessageChannel.class);
    when(this.channel.getProductID()).thenReturn(ProductID.STRIPE);
    this.manager = new ClientEntityManagerImpl(this.channel);
    
    String entityClassName = "Class Name";
    String entityInstanceName = "Instance Name";
    this.entityID = new EntityID(entityClassName, entityInstanceName);
    this.instance = new ClientInstanceID(1);
    this.descriptor = EntityDescriptor.createDescriptorForInvoke(new FetchID(1), instance);
  }
 
  public void testResponseSinkFlush() throws Exception {
    
  }

  // Test that a simple lookup will succeed.
  public void testSimpleLookupSuccess() throws Exception {
    // We will create a runnable which will attempt to fetch the entity.
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    
    // Set the target for success.
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    final EntityException resultException = null;
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });    
    // Now we can start the lookup thread.
    fetcher.start();
    // Join on the thread.
    fetcher.join();
    
    // We expect that we found the entity.
    assertTrue(didFindEndpoint(fetcher));
  }
  
  // Test to make sure we can still receive items without error after close, needed due to shutdown sequence
  public void testReceiveAfterClose() throws Exception {
    TransactionID tid = new TransactionID(1L);
    manager.shutdown();
    manager.complete(tid);
    manager.failed(tid, new EntityException(this.entityID.getClassName(), this.entityID.getEntityName(), "", null) {});
    manager.received(tid);
    manager.retired(tid);
    // nothing should throw exception
  }  

  // Test that a simple lookup can fail.
  public void testSimpleLookupFailure() throws Exception {
    // We will create a runnable which will attempt to fetch the entity.
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    
    // Set the target for failure.
    final byte[] resultObject = null;
    final EntityException resultException = new EntityNotFoundException(null, null);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });       
    // Now we can start the lookup thread.
    fetcher.start();
    // Join on the thread.
    fetcher.join();
    
    // We expect that we couldn't find the entity.
    assertFalse(didFindEndpoint(fetcher));
  }

  // Test pause will block progress but it the lookup will complete (failing to find the entity) after unpause.
  public void testLookupStalledByPause() throws Exception {
    final EntityException resultException = new EntityNotFoundException(null, null);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, null, resultException, true);
      }
    });    
    // We will create a runnable which will attempt to fetch the entity.
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    
    // Pause the manager before we start anything.
    this.manager.pause();
    // Now we can start the lookup thread as we expect it to stall on the paused state.
    fetcher.start();
    // Spin until we can observe that this thread is in the WAITING state.
    while (fetcher.isAlive() && fetcher.getState() != Thread.State.WAITING) {
      ThreadUtil.reallySleep(1000);
    }
    // Now unpause to ensure it progresses.
    this.manager.unpause();
    // Join on the thread.
    fetcher.join();
    
    // We expect that we couldn't find the entity.
    assertFalse(didFindEndpoint(fetcher));
  }
  
  // Test fetch+release on success.
  public void testFetchReleaseOnSuccess() throws Exception {
    // We will create a runnable which will attempt to fetch the entity.
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    
    // Set the target for success.
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    final EntityException resultException = null;
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });       
    // Now we can start the lookup thread.
    fetcher.start();
    // Join on the thread.
    fetcher.join();
    
    // We expect that we found the entity.
    assertTrue(didFindEndpoint(fetcher));
  }

  // Test fetch+release on failure.
  public void testFetchReleaseOnFailure() throws Exception {
    // We will create a runnable which will attempt to fetch the entity.
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    
    // Set the target for failure.
    final byte[] resultObject = null;
    final EntityException resultException = new EntityNotFoundException(null, null);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });       
    // Now we can start the lookup thread.
    fetcher.start();
    // Join on the thread.
    fetcher.join();
    
    // We expect that we couldn't find the entity.
    assertFalse(didFindEndpoint(fetcher));
    
    // Now, release it and expect to see the exception thrown, directly (since we are accessing the manager, directly).
    boolean didRelease = false;
    try {
      fetcher.close();
      didRelease = true;
    } catch (RuntimeException e) {
      didRelease = false;
    }
    assertFalse(didRelease);
  }

  public void testFetchandAsyncRelease() throws Exception {
    // We will create a runnable which will attempt to fetch the entity.
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    
    // Set the target for success.
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    final EntityException resultException = null;
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });   
    // Now we can start the lookup thread.
    fetcher.start();
    // Join on the thread.
    fetcher.join();
    
    try {
      EntityClientEndpoint endpoint = fetcher.getResult();
    } catch (EntityNotFoundException not) {
      Assert.fail();
    }
    
    // Now, release it and expect to see the exception thrown, directly (since we are accessing the manager, directly).
    boolean didRelease = false;
    try {
      Future<Void> released = fetcher.release();
      released.get();
      didRelease = true;
    } catch (Exception e) {
      didRelease = false;
    }
    assertTrue(didRelease);
  }
  
  
  public void testShutdownCausesReleaseException() throws Exception {
    // We will create a runnable which will attempt to fetch the entity.
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    
    // Set the target for success.
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    final EntityException resultException = null;
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });   
    // Now we can start the lookup thread.
    fetcher.start();
    // Join on the thread.
    fetcher.join();
    
    try {
      EntityClientEndpoint endpoint = fetcher.getResult();
    } catch (EntityNotFoundException not) {
      Assert.fail();
    }
    
    // Now, release it and expect to see the exception thrown, directly (since we are accessing the manager, directly).
    boolean didRelease;
    this.manager.shutdown();
    try {
      Future<Void> released = fetcher.release();
      released.get();
      didRelease = true;
    } catch (ExecutionException e) {
      // Expected.
      didRelease = false;
    }
    assertTrue(didRelease);
  }
  
  // That that we can shut down while in a paused state without locking up.
  public void testShutdownWhilePaused() throws Exception {
    // We will create a runnable which will attempt to fetch the entity (and we will get this stuck in "WAITING" on pause).
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    final EntityException resultException = null;
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, false);
      }
    });
    // Pause the manager before we start anything.
    this.manager.pause();
    // Now we can start the lookup thread as we expect it to stall on the paused state.
    fetcher.start();
    // Spin until we can observe that this thread is in the WAITING state.
    while (fetcher.isAlive() && fetcher.getState() != Thread.State.WAITING) {
      ThreadUtil.reallySleep(1000);
    }
    
    // Now, shut down the manager.
    this.manager.shutdown();
    // Join on the waiter thread since the shutdown should have released it to fail in the expected way.
    fetcher.join();
    
    // We are expecting a TCNotRunningException.
    try {
      fetcher.getResult();
      fail();
    } catch (ConnectionClosedException e) {
      // Expected.
    } catch (Throwable t) {
      // Unexpected.
      fail();
    }
  }

  public void testInvoke() throws Exception {
    final byte[] messageObject = new byte[8];
    ByteBuffer.wrap(messageObject).putLong(0xFFFFFFFFL);
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, null, true);
      }
    });
    EntityClientEndpoint endpoint = this.manager.fetchEntity(entityID, 1L, instance, new ByteArrayMessageCodec());

    Future future = endpoint.message(new ByteArrayEntityMessage(messageObject)).invoke();
    ByteArrayEntityResponse response = (ByteArrayEntityResponse) future.get(5, TimeUnit.SECONDS);
    assertThat(Arrays.deepEquals(new Object[]{response.getResponse()}, new Object[]{resultObject}), is(true));
  }

  public void testInvokeException() throws Exception {
    final byte[] messageObject = new byte[8];
    ByteBuffer.wrap(messageObject).putLong(0xFFFFFFFFL);
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      int counter = 0;
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        // the 1st message ("fetch") needs to be successful
        if (counter++ > 0) {
          return new TestRequestBatchMessage(manager, null, new EntityException("a.class.name", "an.entity.name", "mock error", new RuntimeException("boom!")) {}, true);
        }
        return new TestRequestBatchMessage(manager, resultObject, null, true);
      }
    });
    EntityClientEndpoint endpoint = this.manager.fetchEntity(entityID, 1L, instance, new ByteArrayMessageCodec());

    try {
      endpoint.message(new ByteArrayEntityMessage(messageObject)).invoke().get(10, TimeUnit.SECONDS);
      fail("expected EntityException");
    } catch (ExecutionException ee) {
      assertThat(ee.getCause(), instanceOf(EntityException.class));
    }
  }

  public void testAsyncInvoke() throws Exception {
    final byte[] messageObject = new byte[8];
    ByteBuffer.wrap(messageObject).putLong(0xFFFFFFFFL);
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, null, true);
      }
    });
    EntityClientEndpoint endpoint = this.manager.fetchEntity(entityID, 1L, instance, new ByteArrayMessageCodec());

    AuditingInvocationCallback callback = new AuditingInvocationCallback();
    endpoint.message(new ByteArrayEntityMessage(messageObject)).invoke(callback, allOf(Types.class));

    int i = 0;
    assertThat(callback.events.get(i++), is(Acks.SENT));
    assertThat(callback.events.get(i++), is(Acks.RECEIVED));
    assertThat(Arrays.deepEquals(new Object[]{((ByteArrayEntityResponse) callback.events.get(i++)).response}, new Object[]{resultObject}), is(true));
    assertThat(callback.events.get(i++), is(Acks.COMPLETED));
    assertThat(callback.events.get(i++), is(Acks.RETIRED));
    assertThat(callback.events.size(), is(5));
  }

  public void testAsyncInvokeException() throws Exception {
    final byte[] messageObject = new byte[8];
    ByteBuffer.wrap(messageObject).putLong(0xFFFFFFFFL);
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      int counter = 0;
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        // the 1st message ("fetch") needs to be successful
        if (counter++ > 0) {
          return new TestRequestBatchMessage(manager, null, new EntityException("a.class.name", "an.entity.name", "mock error", new RuntimeException("boom!")) {}, true);
        }
        return new TestRequestBatchMessage(manager, resultObject, null, true);
      }
    });
    EntityClientEndpoint endpoint = this.manager.fetchEntity(entityID, 1L, instance, new ByteArrayMessageCodec());

    AuditingInvocationCallback callback = new AuditingInvocationCallback();
    endpoint.message(new ByteArrayEntityMessage(messageObject)).invoke(callback, allOf(Types.class));

    int i = 0;
    assertThat(callback.events.get(i++), is(Acks.SENT));
    assertThat(callback.events.get(i++), is(Acks.RECEIVED));
    EntityException failure = (EntityException) callback.events.get(i++);
    assertThat(callback.events.get(i++), is(Acks.COMPLETED));
    assertThat(failure.getClassName(), is("a.class.name"));
    assertThat(failure.getEntityName(), is("an.entity.name"));
    assertThat(failure.getDescription(), is("mock error"));
    assertThat(failure.getCause().getClass().getName(), is(RuntimeException.class.getName()));
    assertThat(failure.getCause().getMessage(), is("boom!"));

    assertThat(callback.events.size(), is(5));
  }

  public void testThreadInterruptsDontCauseSendIssues() throws Exception {
    // Set the target for success.
    final byte[] resultObject = new byte[8];
    ByteBuffer.wrap(resultObject).putLong(1L);
    final EntityException resultException = null;
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });       
    TestFetcher fetcher = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    fetcher.interruptAtStart();
    fetcher.start();
    fetcher.join();
    try {
      EntityClientEndpoint endpoint = fetcher.getResult();
      Assert.assertNotNull(endpoint);
    } catch (Exception e) {
      e.printStackTrace();
      // not expected
      throw e;
    }
  }

  // Test that concurrent lookups of the same non-existent entity both fail in the expected way, raising no exceptions.
  public void testObjectNotFoundConcurrentLookup() throws Exception {
    // Configure the test to return the failure for this.
    final byte[] resultObject = null;
    final EntityException resultException = new EntityNotFoundException(null, null);
    when(channel.createMessage(Mockito.eq(TCMessageType.VOLTRON_ENTITY_MESSAGE))).then(new Answer<TCAction>() {
      @Override
      public TCAction answer(InvocationOnMock invocation) throws Throwable {
        return new TestRequestBatchMessage(manager, resultObject, resultException, true);
      }
    });
    
    TestFetcher fetcher1 = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    TestFetcher fetcher2 = new TestFetcher(this.manager, this.entityID, 1L, this.instance);
    fetcher1.start();
    fetcher2.start();
    fetcher1.join();
    fetcher2.join();

    // We expect that both should have failed to find the entity.
    assertFalse(didFindEndpoint(fetcher1));
    assertFalse(didFindEndpoint(fetcher2));
  }

  public void testPauseUnpause() {
    this.manager.pause();
    try {
      this.manager.pause();
      fail();
    } catch (final AssertionError e) {
      // expected assertion
    }

    this.manager.unpause();
    try {
      this.manager.unpause();
      fail();
    } catch (final AssertionError e) {
      // expected assertion
    }
  }
  
  @Test
  public void testSingleInvoke() throws Exception {
    byte[] resultObject = new byte[0];
    EntityException resultException = null;
    TestRequestBatchMessage message = new TestRequestBatchMessage(this.manager, resultObject, resultException, true);
    when(channel.createMessage(TCMessageType.VOLTRON_ENTITY_MESSAGE)).thenReturn(message);

    CompletableFuture<byte[]> result = new CompletableFuture<>();
    SafeInvocationCallback<byte[]> callback = new SafeInvocationCallback<byte[]>() {
      @Override
      public void result(byte[] response) {
        result.complete(response);
      }
    };
    this.manager.invokeAction(entityID, descriptor, EnumSet.noneOf(Types.class), callback, false, new byte[0]);
    // We are waiting for no ACKs so this should be available since the send will trigger the delivery.
    byte[] last = result.get();
    assertTrue(resultObject == last);
  }


  @Test
  public void testSingleInvokeTimeout() throws Exception {
    byte[] resultObject = new byte[0];
    EntityException resultException = null;
    TestRequestBatchMessage message = new TestRequestBatchMessage(this.manager, resultObject, resultException, false);
    when(channel.createMessage(TCMessageType.VOLTRON_ENTITY_MESSAGE)).thenReturn(message);

    CompletableFuture<byte[]> result = new CompletableFuture<>();
    SafeInvocationCallback<byte[]> callback = new SafeInvocationCallback<byte[]>() {
      @Override
      public void result(byte[] response) {
        result.complete(response);
      }
    };
    this.manager.invokeAction(entityID, descriptor, EnumSet.noneOf(Types.class), callback, false, new byte[0]);
    // We are waiting for no ACKs so this should be available since the send will trigger the delivery.
    long start = System.currentTimeMillis();
    try {
      result.get(1, TimeUnit.SECONDS);
      Assert.fail();
    } catch (TimeoutException to) {
      assertTrue(!result.isDone());
      assertThat(System.currentTimeMillis() - start, Matchers.greaterThanOrEqualTo(1000L));
      //  expected
    }
    start = System.currentTimeMillis();
    try {
      result.get(2, TimeUnit.SECONDS);
      Assert.fail();
    } catch (TimeoutException to) {
      assertTrue(!result.isDone());
      assertThat(System.currentTimeMillis() - start, Matchers.greaterThanOrEqualTo(2000L));
      //  expected
    }
  }  

  @Test
  public void testCreate() throws Exception {
    long version = 1;
    byte[] config = new byte[0];
    byte[] resultObject = new byte[0];
    EntityException resultException = null;
    TestRequestBatchMessage message = new TestRequestBatchMessage(this.manager, resultObject, resultException, true);
    when(channel.createMessage(TCMessageType.VOLTRON_ENTITY_MESSAGE)).thenReturn(message);
    this.manager.createEntity(entityID, version, config);
    // We are waiting for no ACKs so this should be available since the send will trigger the delivery.
  }

  private boolean didFindEndpoint(TestFetcher fetcher) throws Exception {
    boolean didFind = false;
    try {
      EntityClientEndpoint<EntityMessage, EntityResponse> endpoint = fetcher.getResult();
      didFind = true;
      endpoint.close();
    } catch (EntityNotFoundException e) {
      // This is how we flag something as not found.
      didFind = false;
    }
    return didFind;
  }


  private static class TestFetcher extends Thread {
    private final ClientEntityManager manager;
    private final EntityID entity;
    private final long version;
    private boolean interrupt = false;
    private final ClientInstanceID instance;
    private final MessageCodec codec;
    private EntityClientEndpoint<EntityMessage, EntityResponse> result;
    private Exception exception;
    
    public TestFetcher(ClientEntityManager manager, EntityID entity, long version, ClientInstanceID instance) {
      this(manager, entity, version, instance, mock(MessageCodec.class));
    }

    public TestFetcher(ClientEntityManager manager, EntityID entity, long version, ClientInstanceID instance, MessageCodec codec) {
      this.manager = manager;
      this.entity = entity;
      this.version = version;
      this.instance = instance;
      this.codec = codec;
    }
    @SuppressWarnings("unchecked")
    @Override
    public void run() {
      try {
        if (this.interrupt) {
          this.interrupt();
        }
        this.result = this.manager.fetchEntity(entity, version, instance, codec);
        if (this.interrupt) {
          Assert.assertTrue(this.isInterrupted());
        }
      } catch (Exception t) {
        this.exception = t;
      }
    }
    public EntityClientEndpoint<EntityMessage, EntityResponse> getResult() throws Exception {
      if (null != this.exception) {
        throw this.exception;
      }
      return this.result;
    }
    
    public void interruptAtStart() {
      interrupt = true;
    }
    
    public void close() {
      result.close();
    }
    
    public Future<Void> release() {
      return result.release();
    }
  }
  
  
  private static class TestRequestBatchMessage implements NetworkVoltronEntityMessage {
    private final ClientEntityManager clientEntityManager;
    private final byte[] resultObject;
    private final EntityException resultException;
    private final boolean autoComplete;
    private TransactionID transactionID;
    private EntityDescriptor descriptor;
    private EntityID entityID;
    private TCByteBuffer extendedData;
    private boolean requiresReplication;
    private Type type;
    
    public TestRequestBatchMessage(ClientEntityManager clientEntityManager, byte[] resultObject, EntityException resultException, boolean autoComplete) {
      this.clientEntityManager = clientEntityManager;
      this.resultObject = resultObject;
      this.resultException = resultException;
      this.autoComplete = autoComplete;
    }
    public void explicitComplete(byte[] explicitResult, EntityException resultException) {
      Assert.assertFalse(this.autoComplete);
      if (null != explicitResult) {
        this.clientEntityManager.complete(this.transactionID, explicitResult);
      } else {
        if (null != resultException) {
          this.clientEntityManager.failed(this.transactionID, resultException);
        } else {
          this.clientEntityManager.complete(this.transactionID);
        }
      }
    }
    @Override
    public TransactionID getTransactionID() {
      return this.transactionID;
    }

    @Override
    public EntityID getEntityID() {
      return this.entityID;
    }

    @Override
    public Set<Acks> getRequestedAcks() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean doesRequestReceived() {
      return true;
    }

    @Override
    public boolean doesRequestRetired() {
      return false;
    }
    
    @Override
    public TCMessageType getMessageType() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void hydrate() throws IOException, UnknownNameException {
      throw new UnsupportedOperationException();
    }

    boolean sent = false;
    @Override
    public NetworkRecall send() {
      assertFalse(sent);
      sent = true;
      if (this.autoComplete) {
        if (null != this.resultObject) {
          this.clientEntityManager.complete(this.transactionID, this.resultObject);
        } else {
          if (null != this.resultException) {
            this.clientEntityManager.failed(this.transactionID, this.resultException);
          } else {
            this.clientEntityManager.complete(this.transactionID);
          }
        }
        this.clientEntityManager.retired(this.transactionID);
      }
      return mock(NetworkRecall.class);
    }

    @Override
    public MessageChannel getChannel() {
      throw new UnsupportedOperationException();
    }
    @Override
    public NodeID getSourceNodeID() {
      throw new UnsupportedOperationException();
    }
    @Override
    public NodeID getDestinationNodeID() {
      throw new UnsupportedOperationException();
    }
    @Override
    public SessionID getLocalSessionID() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ClientID getSource() {
      throw new UnsupportedOperationException();
    }
    @Override
    public EntityDescriptor getEntityDescriptor() {
      return this.descriptor;
    }
    @Override
    public boolean doesRequireReplication() {
      return this.requiresReplication;
    }
    @Override
    public Type getVoltronType() {
      return type;
    }
    @Override
    public TCByteBuffer getExtendedData() {
      return this.extendedData.asReadOnlyBuffer();
    }
    @Override
    public TransactionID getOldestTransactionOnClient() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void setContents(ClientID clientID, TransactionID transactionID, EntityID eid, EntityDescriptor entityDescriptor, 
            Type type, boolean requiresReplication, TCByteBuffer extendedData, TransactionID oldestTransactionPending, Set<Acks> acks) {
      this.transactionID = transactionID;
      Assert.assertNotNull(eid);
      this.entityID = eid;
      this.descriptor = entityDescriptor;
      this.extendedData = extendedData;
      this.requiresReplication = requiresReplication;
      this.type = type;
    }

    @Override
    public void setMessageCodecSupplier(MessageCodecSupplier supplier) {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public EntityMessage getEntityMessage() {
      throw new UnsupportedOperationException();
    }
  }
  
  private static class FakeSink implements Sink<Object> {
    
    private final EventHandler<Object> handle;

    public FakeSink(EventHandler<Object> handle) {
      this.handle = handle;
    }

    @Override
    public void addToSink(Object context) {
      try {
        handle.handleEvent(context);
      } catch (EventHandlerException e) {
        throw new RuntimeException(e);
      }
    }
  }


  static class ByteArrayMessageCodec implements MessageCodec {
    @Override
    public byte[] encodeMessage(EntityMessage message) throws MessageCodecException {
      return ((ByteArrayEntityMessage) message).getMessage();
    }
    @Override
    public EntityMessage decodeMessage(byte[] payload) throws MessageCodecException {
      return new ByteArrayEntityMessage(payload);
    }
    @Override
    public byte[] encodeResponse(EntityResponse response) throws MessageCodecException {
      return ((ByteArrayEntityResponse) response).getResponse();
    }
    @Override
    public EntityResponse decodeResponse(byte[] payload) throws MessageCodecException {
      return new ByteArrayEntityResponse(payload);
    }
  }

  static class ByteArrayEntityMessage implements EntityMessage {
    private final byte[] message;
    public ByteArrayEntityMessage(byte[] message) {
      this.message = message;
    }
    public byte[] getMessage() {
      return message;
    }
    @Override
    public String toString() {
      return "ByteArrayEntityMessage " + Arrays.toString(message);
    }
  }

  static class ByteArrayEntityResponse implements EntityResponse {
    private final byte[] response;
    public ByteArrayEntityResponse(byte[] response) {
      this.response = response;
    }
    public byte[] getResponse() {
      return response;
    }
    @Override
    public String toString() {
      return "ByteArrayEntityResponse " + Arrays.toString(response);
    }
  }

  static class AuditingInvocationCallback implements InvocationCallback<ByteArrayEntityResponse> {
    final List<Object> events = new CopyOnWriteArrayList<>();

    @Override
    public void sent() {
      events.add(Acks.SENT);
    }

    @Override
    public void received() {
      events.add(Acks.RECEIVED);
    }

    @Override
    public void result(ByteArrayEntityResponse response) {
      events.add(response);
    }

    @Override
    public void failure(Throwable failure) {
      events.add(failure);
    }

    @Override
    public void complete() {
      events.add(Acks.COMPLETED);
    }

    @Override
    public void retired() {
      events.add(Acks.RETIRED);
    }
  }
}
