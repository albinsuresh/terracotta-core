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
package com.terracotta.connection.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.connection.entity.Entity;
import org.terracotta.connection.entity.EntityRef;
import org.terracotta.entity.EntityClientEndpoint;
import org.terracotta.entity.EntityClientService;
import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.EntityResponse;
import org.terracotta.exception.EntityAlreadyExistsException;
import org.terracotta.exception.EntityConfigurationException;
import org.terracotta.exception.EntityException;
import org.terracotta.exception.EntityNotFoundException;
import org.terracotta.exception.EntityNotProvidedException;
import org.terracotta.exception.EntityVersionMismatchException;
import org.terracotta.exception.PermanentEntityException;

import com.tc.object.ClientEntityManager;
import com.tc.object.ClientInstanceID;
import com.tc.object.EntityID;
import com.tc.util.Assert;
import com.tc.util.Util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.terracotta.entity.EndpointConnector;
import org.terracotta.exception.ConnectionClosedException;


public class TerracottaEntityRef<T extends Entity, C, U> implements EntityRef<T, C, U> {
  private final static Logger logger = LoggerFactory.getLogger(TerracottaEntityRef.class);
  private final Supplier<ClientEntityManager> entityManager;
  private final EndpointConnector endpointConnector;
  private final Class<T> type;
  private final long version;
  private final String name;
  private final EntityClientService<T, C, ? extends EntityMessage, ? extends EntityResponse, U> entityClientService;

  // Each instance fetched by this ref can be individually addressed by the server so it needs a unique ID.
  private final AtomicLong nextClientInstanceID;

  public TerracottaEntityRef(Supplier<ClientEntityManager> entityManager, EndpointConnector endpointConnector,
                             Class<T> type, long version, String name, EntityClientService<T, C, ? extends EntityMessage, ? extends EntityResponse, U> entityClientService,
                             AtomicLong clientIds) {
    this.entityManager = entityManager;
    this.endpointConnector = endpointConnector;
    this.type = type;
    this.version = version;
    this.name = name;
    this.entityClientService = entityClientService;
    this.nextClientInstanceID = clientIds;
  }

  @Override
  public boolean isValid() {
    return entityManager.get().isValid();
  }

  @Override
  public synchronized T fetchEntity(U userData) throws EntityNotFoundException, EntityVersionMismatchException {
    EntityClientEndpoint endpoint;
    try {
      final ClientInstanceID clientInstanceID = new ClientInstanceID(this.nextClientInstanceID.getAndIncrement());
      endpoint = entityManager.get().fetchEntity(this.getEntityID(), this.version, clientInstanceID, entityClientService.getMessageCodec());
    } catch (EntityNotFoundException | EntityVersionMismatchException e) {
      throw e;
    } catch (EntityException e) {
      throw Assert.failure("Unsupported exception type returned to fetch", e);
    } catch (ConnectionClosedException closed) {
      // just rethrow
      throw closed;
    } catch (final Throwable t) {
      Util.printLogAndRethrowError(t, logger);
      throw t;
    }

    return (T) endpointConnector.connect(endpoint, entityClientService, userData);
  }

  @Override
  public String getName() {
    return name;
  }

  private EntityID getEntityID() {
    return new EntityID(type.getName(), name);
  }

  @Override
  public void create(final C configuration) throws EntityNotProvidedException, EntityAlreadyExistsException, EntityVersionMismatchException, EntityConfigurationException {
    final EntityID entityID = getEntityID();
    try {
      entityManager.get().createEntity(entityID, version, entityClientService.serializeConfiguration(configuration));
    } catch (EntityNotProvidedException | EntityAlreadyExistsException | EntityVersionMismatchException | EntityConfigurationException e) {
      throw e;
    } catch (EntityException e) {
      throw Assert.failure("Unsupported exception type returned to create", e);
    }
  }

  @Override
  public C reconfigure(final C configuration) throws EntityNotProvidedException, EntityNotFoundException, EntityConfigurationException {
    final EntityID entityID = getEntityID();
    try {
      return entityClientService.deserializeConfiguration(entityManager.get().reconfigureEntity(entityID, version, entityClientService.serializeConfiguration(configuration)));
    } catch (EntityNotFoundException | EntityNotProvidedException | EntityConfigurationException e) {
      throw e;
    } catch (EntityException e) {
      throw Assert.failure("Unsupported exception type returned to reconfigure", e);
    }
  }
  
  @Override
  public boolean destroy() throws EntityNotProvidedException, EntityNotFoundException, PermanentEntityException {
    EntityID entityID = getEntityID();
    
    try {
      return entityManager.get().destroyEntity(entityID, this.version);
    } catch (EntityNotProvidedException | EntityNotFoundException e) {
      throw e;
    } catch (EntityException e) {
      throw Assert.failure("Unsupported exception type returned to destroy", e);
    }
  }
}
