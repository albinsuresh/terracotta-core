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
package com.tc.util;

import java.io.Serializable;

/**
 * Generic Identifier class, parent class of many ID types. Legal identifiers are expected to be >= 0 and -1 represents
 * a "null" identifier.
 * 
 * @author steve
 */
public abstract class AbstractIdentifier implements Comparable<AbstractIdentifier>, Serializable {
  private static final long serialVersionUID = 1396710277826990138L;
  private static final long NULL_ID          = -1;
  private final long        id;

  /**
   * Create an identifier with a long value, which is expected to be >= 0.
   */
  public AbstractIdentifier(long id) {
    this.id = id;
  }

  /**
   * Create a null identifier
   */
  protected AbstractIdentifier() {
    this.id = NULL_ID;
  }

  /**
   * Check whether the identifier is null (-1).
   * 
   * @return True if -1, false otherwise
   */
  public boolean isNull() {
    return (this.id == NULL_ID);
  }
  
  public boolean isValid() {
    return (this.id >= 0L);
  }

  /**
   * Convert to long
   * 
   * @return Long identifier value
   */
  public final long toLong() {
    return id;
  }

  @Override
  public String toString() {
    return getIdentifierType() + "=" + "[" + id + "]";
  }

  /**
   * Subclasses of AbstractIdentifier specify their "type" by implementing this method and returning a string. The type
   * is used in printing toString().
   */
  abstract public String getIdentifierType();

  @Override
  public int hashCode() {
    return (int) (this.id ^ (this.id >>> 32));
  }

  /**
   * Equality is based on the id value and the identifier class.
   */
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof AbstractIdentifier) {
      AbstractIdentifier other = (AbstractIdentifier) obj;
      return ((this.id == other.id) && this.getClass().equals(other.getClass()));
    }
    return false;
  }

  @Override
  public int compareTo(AbstractIdentifier other) {
    return (id < other.id ? -1 : (id == other.id ? 0 : 1));
  }
}
