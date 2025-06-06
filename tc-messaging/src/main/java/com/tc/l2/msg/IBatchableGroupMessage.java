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
package com.tc.l2.msg;

import com.tc.net.groups.AbstractGroupMessage;


/**
 * The interfaces of a batch message type which can be used by GroupMessageBatchContext.
 * 
 * @param <E> The underlying batch element type.
 */
public interface IBatchableGroupMessage<E> {
  /**
   * Adds a new element to the existing batch.
   * @param element The new element to add.
   */
  public void addToBatch(E element);

  /**
   * @return The current number of elements in the batch.
   */
  public int getBatchSize();
  
  public long getPayloadSize();
  
  public void setSequenceID(long rid);

  public long getSequenceID();

  /**
   * Casts the message into an AbstractGroupMessage for serialization and transmission over the wire.
   * 
   * @return The receiver.
   */
  public AbstractGroupMessage asAbstractGroupMessage();
}