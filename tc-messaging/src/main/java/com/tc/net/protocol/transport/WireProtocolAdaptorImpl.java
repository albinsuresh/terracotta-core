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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tc.bytes.TCReference;
import com.tc.net.core.TCConnection;
import com.tc.net.protocol.AbstractTCNetworkHeader;
import com.tc.net.protocol.AbstractTCProtocolAdaptor;
import com.tc.net.protocol.TCNetworkHeader;
import com.tc.net.protocol.TCNetworkMessage;
import com.tc.net.protocol.TCProtocolException;

import java.util.Iterator;

/**
 * Connection adaptor to parse wire protocol messages
 * 
 * @author teck
 */
public class WireProtocolAdaptorImpl extends AbstractTCProtocolAdaptor implements WireProtocolAdaptor {
  private static final Logger logger = LoggerFactory.getLogger(WireProtocolAdaptorImpl.class);
  private final WireProtocolMessageSink sink;
  
  protected WireProtocolAdaptorImpl(WireProtocolMessageSink sink) {
    super(logger);
    this.sink = sink;
  }
  
  @Override
  public void addReadData(TCConnection source, TCReference data) throws TCProtocolException {
    packageAndDispatchData(source, data);
  }
  
  private void packageAndDispatchData(TCConnection source, TCReference data) throws TCProtocolException {
    final WireProtocolMessage msg;
    try {
      msg = (WireProtocolMessage) this.processIncomingData(source, data);
    } catch (TCProtocolException e) {
      init();
      throw e;
    }
    if (msg != null) {
      init();
      if (logger.isDebugEnabled()) {
        logger.debug("\nRECEIVE\n" + msg.toString());
      }
      if (msg.getWireProtocolHeader().isMessagesGrouped()) {
        WireProtocolGroupMessage wpmg = (WireProtocolGroupMessage) msg;
        try {
          for (Iterator<TCNetworkMessage> i = wpmg.getMessageIterator(); i.hasNext();) {
            WireProtocolMessage wpm = (WireProtocolMessage) i.next();
            dispatch(wpm);
          }
        } finally {
          wpmg.complete();
        }
      } else {
        dispatch(msg);
      }
    }
  }
  
  private void dispatch(WireProtocolMessage msg) throws WireProtocolException {
    try {
      sink.putMessage(msg);
    } finally {
      msg.complete();
    }
  }

  @Override
  protected AbstractTCNetworkHeader getNewProtocolHeader() {
    return new WireProtocolHeader();
  }

  @Override
  protected int computeDataLength(TCNetworkHeader header) {
    WireProtocolHeader wph = (WireProtocolHeader) header;
    return wph.getTotalPacketLength() - wph.getHeaderByteLength();
  }

  @Override
  protected TCNetworkMessage createMessage(TCConnection source, TCNetworkHeader hdr, TCReference data)
      throws TCProtocolException {
    if (data == null) { throw new TCProtocolException("Wire protocol messages must have a payload"); }
    WireProtocolHeader wph = (WireProtocolHeader) hdr;
    final WireProtocolMessage rv;

    if (wph.isHandshakeOrHealthCheckMessage()) {
      rv = new TransportMessageImpl(source, wph, data);
    } else {
      if (wph.isMessagesGrouped()) {
        rv = new WireProtocolGroupMessageImpl(source, wph, data);
      } else {
        rv = new WireProtocolMessageImpl(source, wph, data);
      }
    }

    return rv;
  }
}
