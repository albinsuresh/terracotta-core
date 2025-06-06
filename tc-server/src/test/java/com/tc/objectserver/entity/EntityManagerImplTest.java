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
package com.tc.objectserver.entity;

import com.tc.async.api.Sink;
import com.tc.classloader.ServiceLocator;
import com.tc.net.ClientID;
import com.tc.object.ClientInstanceID;
import com.tc.object.EntityDescriptor;
import org.junit.Before;
import org.junit.Test;

import com.tc.object.EntityID;
import com.tc.object.FetchID;
import com.tc.object.session.SessionID;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.api.ManagedEntity;
import com.tc.objectserver.api.ServerEntityAction;
import com.tc.objectserver.api.ServerEntityRequest;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.services.InternalServiceRegistry;
import com.tc.services.TerracottaServiceProviderRegistry;
import com.tc.util.Assert;
import java.util.Collections;
import java.util.Optional;

import static java.util.Optional.empty;
import java.util.Set;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import com.tc.objectserver.api.ManagementKeyCallback;
import com.tc.objectserver.api.ResultCapture;
import com.tc.objectserver.core.impl.ManagementTopologyEventCollector;
import java.util.function.Consumer;
import org.terracotta.monitoring.IMonitoringProducer;


public class EntityManagerImplTest {
  private EntityManagerImpl entityManager;
  private EntityID id;
  private FetchID fetch;
  private long version;
  private long consumerID;

  @SuppressWarnings("unchecked")
  @Before
  public void setUp() throws Exception {
    TerracottaServiceProviderRegistry registry = mock(TerracottaServiceProviderRegistry.class);
    when(registry.subRegistry(any(Long.class))).thenReturn(mock(InternalServiceRegistry.class));
    RequestProcessor processor = mock(RequestProcessor.class);
    ActivePassiveAckWaiter waiter = mock(ActivePassiveAckWaiter.class);
    doAnswer((invoke)->{
        ((Consumer)invoke.getArguments()[6]).accept(waiter);
        return null;
    }).when(processor).scheduleRequest(anyBoolean(), any(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyInt());
    entityManager = new EntityManagerImpl(
        registry,
        mock(ClientEntityStateManager.class),
        new ManagementTopologyEventCollector(mock(IMonitoringProducer.class)),
        processor,
        mock(ManagementKeyCallback.class),
        new ServiceLocator(this.getClass().getClassLoader())
    );
    entityManager.setMessageSink(mock(Sink.class));
    id = new EntityID("com.tc.objectserver.testentity.TestEntity", "foo");
    consumerID = 1L;
    fetch = new FetchID(consumerID);
    version = 1;
  }

  @Test
  public void testGetNonExistent() throws Exception {
    assertThat(entityManager.getEntity(EntityDescriptor.createDescriptorForLifecycle(id, version)), is(empty()));
  }

  @Test
  public void testCreateEntity() throws Exception {
    try {
      entityManager.createEntity(id, version, consumerID);
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    assertThat(entityManager.getEntity(EntityDescriptor.createDescriptorForLifecycle(id, version)).get().getID(), is(id));
  }

  @Test
  public void testCreateExistingEntity() throws Exception {
    ManagedEntity entity = entityManager.createEntity(id, version, consumerID);
    ManagedEntity second = entityManager.createEntity(id, version, consumerID);
    Assert.assertEquals(entity, second);
  }
  
  @Test
  public void testNullEntityChecks() throws Exception {
    Optional<ManagedEntity> check = entityManager.getEntity(EntityDescriptor.createDescriptorForLifecycle(EntityID.NULL_ID, version));
    Assert.assertFalse(check.isPresent());
    ManagedEntity entity = entityManager.createEntity(id, version, consumerID);
    Assert.assertNotNull(entity);
    check = entityManager.getEntity(EntityDescriptor.createDescriptorForLifecycle(id, version));
    Assert.assertTrue(check.isPresent());
    check = entityManager.getEntity(EntityDescriptor.createDescriptorForLifecycle(id, 0));
    //  make sure zero versions go to empty
    Assert.assertTrue(check.isPresent());
    //  make sure null goes to empty
    check = entityManager.getEntity(EntityDescriptor.createDescriptorForLifecycle(EntityID.NULL_ID, 1));
    Assert.assertFalse(check.isPresent());
  }

  @Test
  public void testDestroyEntity() throws Exception {
    entityManager.enterActiveState();
    ManagedEntity entity = entityManager.createEntity(id, version, consumerID);
    Thread.currentThread().setName(ServerConfigurationContext.VOLTRON_MESSAGE_STAGE);
    ServerEntityRequest req = new ServerEntityRequest() {
      @Override
      public ServerEntityAction getAction() {
        return ServerEntityAction.DESTROY_ENTITY;
      }

      @Override
      public ClientID getNodeID() {
        return ClientID.NULL_ID;
      }

      @Override
      public TransactionID getTransaction() {
        return TransactionID.NULL_ID;
      }

      @Override
      public TransactionID getOldestTransactionOnClient() {
        return TransactionID.NULL_ID;
      }

      @Override
      public ClientInstanceID getClientInstance() {
        return ClientInstanceID.NULL_ID;
      }

      @Override
      public boolean requiresReceived() {
        return false;
      }

      @Override
      public Set<SessionID> replicateTo(Set<SessionID> passives) {
        return Collections.emptySet();
      }
    };
    //  set the destroyed flag in the entity
    entity.addRequestMessage(req, MessagePayload.emptyPayload(), mock(ResultCapture.class));
    //  remove it from the manager
    entityManager.removeDestroyed(fetch);
    assertThat(entityManager.getEntity(EntityDescriptor.createDescriptorForLifecycle(id, version)), is(empty()));
  }

}
