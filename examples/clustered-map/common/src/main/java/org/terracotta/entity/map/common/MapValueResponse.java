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
package org.terracotta.entity.map.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


public class MapValueResponse implements MapResponse {
  private final Object value;

  public MapValueResponse(Object value) {
    this.value = value;
  }

  public Object getValue() {
    return this.value;
  }

  @Override
  public Type responseType() {
    return Type.MAP_VALUE;
  }

  @Override
  public void writeTo(DataOutput output) throws IOException {
    PrimitiveCodec.writeTo(output, this.value);
  }

  static MapValueResponse readFrom(DataInput input) throws IOException {
    return new MapValueResponse(PrimitiveCodec.readFrom(input));
  }
}
