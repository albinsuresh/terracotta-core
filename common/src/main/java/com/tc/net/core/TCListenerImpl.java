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
package com.tc.net.core;

import com.tc.net.TCSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tc.net.core.event.TCConnectionEventListener;
import com.tc.net.core.event.TCListenerEvent;
import com.tc.net.core.event.TCListenerEventListener;
import com.tc.net.protocol.ProtocolAdaptorFactory;
import com.tc.util.Assert;
import com.tc.util.TCTimeoutException;
import com.tc.util.concurrent.SetOnceFlag;
import com.tc.util.concurrent.TCExceptionResultException;
import com.tc.util.concurrent.TCFuture;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CopyOnWriteArraySet;
import com.tc.net.protocol.TCProtocolAdaptor;
import java.net.InetSocketAddress;

/**
 * TCListener implementation
 * 
 * @author teck
 */
final class TCListenerImpl implements TCListener {
  protected final static Logger logger = LoggerFactory.getLogger(TCListener.class);

  private final ServerSocketChannel                          ssc;
  private final TCConnectionEventListener                    listener;
  private final TCConnectionManagerImpl                      parent;
  private final InetSocketAddress                              sockAddr;
  private final TCListenerEvent                              staticEvent;
  private final SetOnceFlag                                  closeEventFired = new SetOnceFlag();
  private final SetOnceFlag                                  stopPending     = new SetOnceFlag();
  private final SetOnceFlag                                  stopped         = new SetOnceFlag();
  private final CopyOnWriteArraySet<TCListenerEventListener> listeners       = new CopyOnWriteArraySet<>();
  private final ProtocolAdaptorFactory                       factory;
  private final CoreNIOServices                              commNIOServiceThread;
  private final SocketEndpointFactory                         socketEndpointFactory;

  TCListenerImpl(ServerSocketChannel ssc, ProtocolAdaptorFactory factory, TCConnectionEventListener listener,
                 TCConnectionManagerImpl managerJDK14, CoreNIOServices commNIOServiceThread, SocketEndpointFactory bufferManagerFactory) throws IOException {
    this.sockAddr = new InetSocketAddress(ssc.socket().getInetAddress(), ssc.socket().getLocalPort());
    this.socketEndpointFactory = bufferManagerFactory;
    this.factory = factory;
    this.staticEvent = new TCListenerEvent(this);
    this.ssc = ssc;
    this.listener = listener;
    this.parent = managerJDK14;
    this.commNIOServiceThread = commNIOServiceThread;
  }

  protected void stopImpl(Runnable callback) {
    commNIOServiceThread.stopListener(ssc, callback);
  }

  TCConnectionImpl createConnection(SocketChannel ch, CoreNIOServices nioServiceThread, SocketParams socketParams)
      throws IOException {
    TCProtocolAdaptor adaptor = getProtocolAdaptorFactory().getInstance();
    TCConnectionImpl rv = new TCConnectionImpl(listener, adaptor, ch, parent, nioServiceThread, socketParams, socketEndpointFactory);
    rv.finishConnect();
    parent.newConnection(rv);
    return rv;
  }

  @Override
  public final InetSocketAddress getBindSocketAddress() {
    return this.sockAddr;
  }

  @Override
  public final void stop() {
    if (stopped.isSet()) {
      logger.warn("listener already stopped");
      return;
    }

    if (stopPending.attemptSet()) {
      final TCFuture future = new TCFuture();

      stopImpl(() -> {
        future.set("stop done");
      });

      try {
        future.get();
      } catch (InterruptedException e) {
        logger.warn("stop interrupted");
        Thread.currentThread().interrupt();
      } catch (TCExceptionResultException e) {
        logger.error("Exception: ", e);
        Assert.eval("exception result set in future", false);
      } finally {
        stopped.set();
        fireCloseEvent();
      }
    } else {
      logger.warn("stop already requested");
    }
  }
  
  @Override
  public final void addEventListener(TCListenerEventListener lsnr) {
    if (lsnr == null) {
      logger.warn("trying to add a null event listener");
      return;
    }

    listeners.add(lsnr);
  }

  @Override
  public final void removeEventListener(TCListenerEventListener lsnr) {
    if (lsnr == null) {
      logger.warn("trying to remove a null event listener");
      return;
    }

    listeners.remove(lsnr);
  }

  @Override
  public final boolean isStopped() {
    return stopPending.isSet() || stopped.isSet();
  }

  @Override
  public final String toString() {
    return TCSocketAddress.getStringForm(sockAddr);
  }

  protected final void fireCloseEvent() {
    if (closeEventFired.attemptSet()) {
      for (TCListenerEventListener lsnr : listeners) {
        
        try {
          lsnr.closeEvent(staticEvent);
        } catch (Exception e) {
          logger.error("exception in close event handler", e);
        }
      }
    }
  }

  final ProtocolAdaptorFactory getProtocolAdaptorFactory() {
    return factory;
  }
}
