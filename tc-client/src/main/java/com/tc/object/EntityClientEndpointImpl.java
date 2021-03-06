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

package com.tc.object;

import org.terracotta.entity.AsyncInvocationBuilder;
import org.terracotta.entity.EndpointDelegate;
import org.terracotta.entity.EntityClientEndpoint;
import org.terracotta.entity.InvocationBuilder;
import org.terracotta.entity.InvocationCallback;
import org.terracotta.entity.InvokeFuture;
import org.terracotta.entity.MessageCodec;
import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.EntityResponse;
import org.terracotta.entity.MessageCodecException;

import com.tc.entity.VoltronEntityMessage;
import com.tc.text.MapListPrettyPrint;
import com.tc.util.Assert;
import org.terracotta.exception.EntityException;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.entity.InvokeMonitor;


public class EntityClientEndpointImpl<M extends EntityMessage, R extends EntityResponse> implements EntityClientEndpoint<M, R> {

  private static Logger LOGGER = LoggerFactory.getLogger(EntityClientEndpointImpl.class);

  private final InvocationHandler invocationHandler;
  private final byte[] configuration;
  private final EntityDescriptor invokeDescriptor;
  private final EntityID entityID;
  private final long version;
  private final MessageCodec<M, R> codec;
  private final Runnable closeHook;
  private final ExecutorService closer;
  private EndpointDelegate<R> delegate;
  private boolean isOpen;
  private Future<Void> releaseFuture;
  private final InFlightStats stats = new InFlightStats();
  /**
   * @param eid The type name name of the target entity
   * @param version the version of the entity targeted
   * @param instance the combination of the FetchID from the server and the ClientInstanceID from the client
   * @param invocationHandler Called to handle "invokeAction" requests made on this end-point.
   * @param entityConfiguration Opaque byte[] describing how to configure the entity to be built on top of this end-point.
   * @param closeHook A Runnable which will be run last when the end-point is closed.
   */
  public EntityClientEndpointImpl(EntityID eid, long version, EntityDescriptor instance, InvocationHandler invocationHandler, byte[] entityConfiguration, MessageCodec<M, R> codec, Runnable closeHook, ExecutorService closer) {
    this.entityID = eid;
    this.version = version;
    this.invokeDescriptor = instance;
    this.invocationHandler = invocationHandler;
    this.configuration = entityConfiguration;
    this.codec = codec;
    this.closeHook = closeHook;
    this.closer = closer;
    // We start in the open state.
    this.isOpen = true;
  }
  
  EntityID getEntityID() {
    return this.entityID;
  }
  
  long getVersion() {
    return this.version;
  }
  
  EntityDescriptor getEntityDescriptor() {
    return this.invokeDescriptor;
  }
  
  

  @Override
  public byte[] getEntityConfiguration() {
    // This is harmless while closed but shouldn't be called so check open.
    checkEndpointOpen();
    return configuration;
  }

  @Override
  public void setDelegate(EndpointDelegate<R> delegate) {
    // This is harmless while closed but shouldn't be called so check open.
    checkEndpointOpen();
    Assert.assertNull(this.delegate);
    this.delegate = delegate;
  }
  
  public void handleMessage(byte[] message) throws MessageCodecException {
    // We technically allow messages to come back from the server, after we are closed, simple because it means that the
    // server hasn't yet handled the close.
    if (null != this.delegate) {
      R messageFromServer = this.codec.decodeResponse(message);
      this.delegate.handleMessage(messageFromServer);
    }
  }
    
  public InFlightStats getStatistics() {
    return stats;
  }
    
  @Override
  public InvocationBuilder<M, R> beginInvoke() {
    // We can't create new invocations when the endpoint is closed.
    checkEndpointOpen();
    return new InvocationBuilderImpl();
  }

  @Override
  public AsyncInvocationBuilder<M, R> beginAsyncInvoke() {
    // We can't create new invocations when the endpoint is closed.
    checkEndpointOpen();
    return new AsyncInvocationBuilderImpl();
  }

  private class AsyncInvocationBuilderImpl implements AsyncInvocationBuilder<M, R> {
    private boolean invoked = false;
    private M request;
    private boolean requiresReplication = true;
    private long time;
    private TimeUnit unit;

    @Override
    public AsyncInvocationBuilder<M, R> replicate(boolean requiresReplication) {
      this.requiresReplication = requiresReplication;
      return this;
    }

    @Override
    public AsyncInvocationBuilder<M, R> message(M request) {
      checkInvoked();
      this.request = request;
      return this;
    }

    @Override
    public AsyncInvocationBuilder<M, R> blockEnqueuing(long time, TimeUnit unit) {
      this.time = time;
      this.unit = unit;
      return this;
    }

    private void checkInvoked() {
      if (invoked) {
        throw new IllegalStateException("Already invoked");
      }
    }

    @Override
    public void invoke(InvocationCallback<R> callback) throws RejectedExecutionException {
      try {
        checkInvoked();
        invoked = true;
        InvokeMonitor<R> monitor = new AsyncInvokeMonitor<>(callback);
        InFlightMonitor<R> ifm = new InFlightMonitor<>(codec, monitor, null);
        //TODO make requested acks configurable and only invoke callback methods of specified ones
        invocationHandler.asyncInvokeAction(entityID, invokeDescriptor, EnumSet.allOf(VoltronEntityMessage.Acks.class), ifm, this.requiresReplication, codec.encodeMessage(request), time, unit);
      } catch (IllegalStateException | MessageCodecException ex) {
        callback.failure(ex);
      }
    }
  }

  static class AsyncInvokeMonitor<R extends EntityResponse> implements InvokeMonitor<R>, AckMonitor {
    private final InvocationCallback<R> callback;

    AsyncInvokeMonitor(InvocationCallback<R> callback) {
      this.callback = callback;
    }

    @Override
    public void ackDelivered(VoltronEntityMessage.Acks ack) {
      switch (ack) {
        case SENT:
          callback.sent();
          break;
        case RETIRED:
          callback.retired();
          break;
        case RECEIVED:
          callback.received();
          break;
        case COMPLETED:
          callback.complete();
          break;
        default:
          callback.failure(new IllegalArgumentException("unknown ack : " + ack));
      }
    }

    @Override
    public void exception(EntityException ee) {
      callback.failure(ee);
    }

    @Override
    public void accept(R r) {
      callback.result(r);
    }
  }

  private class InvocationBuilderImpl implements InvocationBuilder<M, R> {
    private boolean invoked = false;
    private M request;
    private final Set<VoltronEntityMessage.Acks> acks = EnumSet.noneOf(VoltronEntityMessage.Acks.class);
    private boolean requiresReplication = true;
    // By default, we block the get() on the RETIRE ack.
    private boolean shouldBlockGetOnRetire = true;
    private InvokeMonitor<R> monitor;
    private Executor executor;

    // TODO: fill in durability/consistency options here.

    @Override
    public synchronized InvocationBuilderImpl message(M request) {
      checkInvoked();
      this.request = request;
      return this;
    }

    @Override
    public InvocationBuilder<M, R> ackSent() {
      acks.add(VoltronEntityMessage.Acks.SENT);
      return this;
    }

    @Override
    public InvocationBuilder<M, R> ackReceived() {
      acks.add(VoltronEntityMessage.Acks.RECEIVED);
      return this;
    }

    @Override
    public InvocationBuilder<M, R> ackCompleted() {
      acks.add(VoltronEntityMessage.Acks.COMPLETED);
      return this;
    }

    @Override
    public InvocationBuilder<M, R> ackRetired() {
      acks.add(VoltronEntityMessage.Acks.RETIRED);
      return this;
    }

    @Override
    public InvocationBuilder<M, R> monitor(InvokeMonitor<R> consumer) {
      this.monitor = consumer;
      return this;
    }

    @Override
    public InvocationBuilder<M, R> withExecutor(Executor useForDelivery) {
      this.executor = useForDelivery;
      return this;
    }
    
    @Deprecated @Override
    public InvocationBuilder<M, R> asDeferredResponse() {
      return this;
    }    

    @Override
    public InvocationBuilder<M, R> replicate(boolean requiresReplication) {
      this.requiresReplication = requiresReplication;
      return this;
    }

    @Override
    public InvocationBuilder<M, R> blockGetOnRetire(boolean shouldBlock) {
      this.shouldBlockGetOnRetire = shouldBlock;
      return this;
    }
    
    private InvokeFuture<R> returnTypedInvoke(long startTime, final InFlightMessage result) {
      return new InvokeFuture<R>() {
        @Override
        public boolean isDone() {
          return result.isDone();
        }

        @Override
        public R get() throws InterruptedException, EntityException {
          try {
            return codec.decodeResponse(result.get());
          } catch (MessageCodecException e) {
            throw new RuntimeException(e);
          } finally {
            collectStats(startTime, result);
          }
        }

        @Override
        public R getWithTimeout(long timeout, TimeUnit unit) throws InterruptedException, EntityException, TimeoutException {
          try {
            return codec.decodeResponse(result.getWithTimeout(timeout, unit));
          } catch (MessageCodecException e) {
            throw new RuntimeException(e);
          } finally {
            collectStats(startTime, result);
          }
        }

        @Override
        public void interrupt() {
          result.interrupt();
        }

        @Override
        public String toString() {
          return result.prettyPrint(new MapListPrettyPrint()).toString();
        }
      };
    }
    
    @Override
    public synchronized InvokeFuture<R> invokeWithTimeout(long time, TimeUnit units) throws MessageCodecException, InterruptedException, TimeoutException {
      checkInvoked();
      invoked = true;
      InFlightMonitor<R> ifm = (this.monitor != null) ? new InFlightMonitor<>(codec, this.monitor, executor) : null;
      long start = System.nanoTime();
      return returnTypedInvoke(start, invocationHandler.invokeActionWithTimeout(entityID, invokeDescriptor, this.acks, ifm, this.requiresReplication, this.shouldBlockGetOnRetire, time, units, codec.encodeMessage(request)));
    }
    
    private void collectStats(long startTime, InFlightMessage msg) {
      long now = System.nanoTime();
      msg.setStatisticsBoundries(startTime, now);
      msg.runOnRetire(()->{
        stats.collect(msg.collect());
      });
    }

    @Override
    public synchronized InvokeFuture<R> invoke() throws MessageCodecException {
      checkInvoked();
      invoked = true;
      InFlightMonitor<R> ifm = (this.monitor != null) ? new InFlightMonitor<>(codec, this.monitor, executor) : null;
      long startTime = System.nanoTime();
      return returnTypedInvoke(startTime, invocationHandler.invokeAction(entityID, invokeDescriptor, this.acks, ifm, this.requiresReplication, this.shouldBlockGetOnRetire, codec.encodeMessage(request)));
    }

    private void checkInvoked() {
      if (invoked) {
        throw new IllegalStateException("Already invoked");
      }
    }
  }

  public byte[] getExtendedReconnectData() {
    // TODO:  Determine if we need to limit anything here on closed.  The call can come from another thread so it may not
    // yet know that we are closed when the call originated.
    byte[] reconnectData = null;
    if (null != this.delegate) {
      reconnectData = this.delegate.createExtendedReconnectData();
    }
    if (null == reconnectData) {
      reconnectData = new byte[0];
    }
    return reconnectData;
  }

  @Override
  public void close() {
    // We can't close twice.
    checkEndpointOpen();
    if (this.closeHook != null) {
      this.closeHook.run();
    }
    // We also need to invalidate ourselves so we don't continue allowing new messages through when disconnecting.
    this.isOpen = false;
  }

  @Override
  public synchronized Future<Void> release() {
    if (releaseFuture == null) {
      if (this.closer == null) {
        close();
        releaseFuture = CompletableFuture.completedFuture(null);
      } else {
        try {
          Callable<Void> call = new Callable<Void>() {
            @Override
            public Void call() throws Exception {
              close();
              return null;
            }
          };
          releaseFuture = this.closer.submit(call);
        } catch (RejectedExecutionException re) {
          // connection already shutdown
          LOGGER.debug("connection already closed", re);
          releaseFuture = CompletableFuture.completedFuture(null);
        }
      }
    }
    return releaseFuture;
  }

  public void didCloseUnexpectedly() {
    // TODO:  Determine if we need to limit anything here on closed.  The call can come from another thread so it may not
    // yet know that we are closed when the call originated.
    // NOTE:  We do NOT run the close hook in this situation since it is assuming that the close was requested and that the
    // underlying connection is still viable.
    if (null != this.delegate) {
      this.delegate.didDisconnectUnexpectedly();
    }
  }

  private void checkEndpointOpen() {
    if (!this.isOpen) {
      throw new IllegalStateException("Endpoint closed");
    }
  }
}
