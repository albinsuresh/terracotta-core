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
package com.tc.entity;

import java.io.IOException;

import com.tc.bytes.TCByteBuffer;
import com.tc.bytes.TCByteBufferFactory;
import com.tc.bytes.TCReference;
import com.tc.entity.VoltronEntityMessage.Acks;
import com.tc.entity.VoltronEntityMessage.Type;
import com.tc.io.TCByteBufferInputStream;
import com.tc.io.TCByteBufferOutputStream;
import com.tc.net.ClientID;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.tcm.MessageMonitor;
import com.tc.net.protocol.tcm.TCActionNetworkMessage;
import com.tc.net.protocol.tcm.TCMessageHeader;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.net.protocol.tcm.UnknownNameException;
import com.tc.object.EntityDescriptor;
import com.tc.object.EntityID;
import com.tc.object.session.SessionID;
import com.tc.object.tx.TransactionID;
import java.util.EnumSet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;


/**
 * A simple test to ensure that the various use cases of this message type all serialize/deserialize correctly.
 */
public class NetworkVoltronEntityMessageImplTest {
  @Test
  public void testSerialization() throws UnknownNameException, IOException {
    SessionID sessionID = new SessionID(0);
    MessageMonitor monitor = mock(MessageMonitor.class);
    MessageChannel channel = null;
    TCMessageType type = TCMessageType.VOLTRON_ENTITY_MESSAGE;
    TCByteBufferOutputStream outputStream = new TCByteBufferOutputStream(4, 4096);
    NetworkVoltronEntityMessageImpl message = new NetworkVoltronEntityMessageImpl(sessionID, monitor, outputStream, channel, type);
    
    ClientID clientID = new ClientID(1);
    TransactionID transactionID = new TransactionID(2);
    EntityDescriptor entityDescriptor = EntityDescriptor.createDescriptorForLifecycle(EntityID.NULL_ID, 3);
    Type messageType = VoltronEntityMessage.Type.FETCH_ENTITY;
    boolean requiresReplication = false;
    TCByteBuffer extendedData = TCByteBufferFactory.wrap(new byte[1]);
    TransactionID oldestTransactionPending = new TransactionID(1);
    message.setContents(clientID, transactionID, EntityID.NULL_ID, entityDescriptor, messageType, 
            requiresReplication, extendedData, oldestTransactionPending, EnumSet.of(Acks.RECEIVED));
    TCActionNetworkMessage msg = message.getNetworkMessage();

    TCMessageHeader header = (TCMessageHeader) msg.getHeader();
    msg.load();
    TCReference payload = msg.getPayload();
    outputStream.close();
    NetworkVoltronEntityMessageImpl decodingMessage = new NetworkVoltronEntityMessageImpl(SessionID.NULL_ID, monitor, null, header, new TCByteBufferInputStream(payload));
    decodingMessage.hydrate();
    assertEquals(clientID, decodingMessage.getSource());
    assertEquals(transactionID, decodingMessage.getTransactionID());
    assertEquals(entityDescriptor, decodingMessage.getEntityDescriptor());
    assertEquals(messageType, decodingMessage.getVoltronType());
    assertEquals(oldestTransactionPending, decodingMessage.getOldestTransactionOnClient());
  }
}
