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
package com.terracotta.connection;

import org.terracotta.connection.Connection;
import org.terracotta.connection.entity.Entity;
import org.terracotta.connection.entity.EntityRef;
import org.terracotta.entity.EntityClientService;
import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.EntityResponse;
import org.terracotta.exception.ConnectionShutdownException;
import org.terracotta.exception.EntityNotProvidedException;

import com.tc.object.ClientEntityManager;
import com.tc.text.MapListPrettyPrint;
import com.tc.text.PrettyPrintable;
import com.terracotta.connection.entity.TerracottaEntityRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.terracotta.entity.EndpointConnector;
import org.terracotta.exception.ConnectionClosedException;


public class TerracottaConnection implements Connection, PrettyPrintable {
  private final Supplier<ClientEntityManager> entityManager;
  private final EntityClientServiceFactory factory = new EntityClientServiceFactory();
  private final EndpointConnector endpointConnector;
  private final Runnable shutdown;
  private final ConcurrentMap<Class<? extends Entity>, EntityClientService<?, ?, ? extends EntityMessage, ? extends EntityResponse, ?>> cachedEntityServices = new ConcurrentHashMap<Class<? extends Entity>, EntityClientService<?, ?, ? extends EntityMessage, ? extends EntityResponse, ?>>();
  private final AtomicLong  clientIds = new AtomicLong(1); // initialize to 1 because zero client is a special case for uninitialized
  private final Properties connectionPropertiesForReporting;
  
  private boolean isShutdown = false;

  public TerracottaConnection(Properties props, Supplier<ClientEntityManager> entityManager, Runnable shutdown) {
    this(props, entityManager, new EndpointConnectorImpl(), shutdown);
  }

  public TerracottaConnection(Properties props, Supplier<ClientEntityManager> entityManager, EndpointConnector endpointConnector, Runnable shutdown) {
    this.entityManager = entityManager;
    this.endpointConnector = endpointConnector;
    this.shutdown = shutdown;
    this.connectionPropertiesForReporting = props;
  }

  @Override
  public synchronized <T extends Entity, C, U> EntityRef<T, C, U> getEntityRef(Class<T> cls, long version, String name) throws EntityNotProvidedException {
    if (isShutdown) {
      throw new ConnectionShutdownException("Already shut down");
    }
    @SuppressWarnings("unchecked")
    EntityClientService<T, C, ? extends EntityMessage, ? extends EntityResponse, U> service = (EntityClientService<T, C, ? extends EntityMessage, ? extends EntityResponse, U>)getEntityService(cls);
    if (null == service) {
      // We failed to find a provider for this class.
      throw new EntityNotProvidedException(cls.getName(), name);
    }
    return new TerracottaEntityRef<T, C, U>(entityManager, endpointConnector, cls, version, name, service, clientIds);
  }

  private <T extends Entity, U> EntityClientService<T, ?, ? extends EntityMessage, ? extends EntityResponse, U> getEntityService(Class<T> entityClass) {
    @SuppressWarnings("unchecked")
    EntityClientService<T, ?, ? extends EntityMessage, ? extends EntityResponse, U> service = (EntityClientService<T, ?, ? extends EntityMessage, ? extends EntityResponse, U>) cachedEntityServices.get(entityClass);
    if (service == null) {
      service = factory.creationServiceForType(entityClass);
      if (null != service) {
        @SuppressWarnings("unchecked")
        EntityClientService<T, ?, ? extends EntityMessage, ? extends EntityResponse, U> tmp = (EntityClientService<T, ?, ? extends EntityMessage, ? extends EntityResponse, U>) cachedEntityServices.putIfAbsent(entityClass, service);
        service = tmp == null ? service : tmp;
      }
    }
    return service;
  }

  @Override
  public synchronized void close() {
    if (!isShutdown) {
      shutdown.run();
      isShutdown = true;
    }
  }

  @Override
  public boolean isValid() {
    try {
      return entityManager.get().isValid();
    } catch (ConnectionClosedException c) {
      // swallow this, we are checking for valid connection
      return false;
    }
  }

  @Override
  public Map<String, ?> getStateMap() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("props" , connectionPropertiesForReporting);
    try {
      state.put("stats" , entityManager.get().getStateMap());
    } catch (Exception e) {
      // silent
    }
    return state;
  }

  @Override
  public String toString() {
    MapListPrettyPrint print = new MapListPrettyPrint();
    this.prettyPrint(print);
    return "TerracottaConnection{" + print + '}';
  }
}
