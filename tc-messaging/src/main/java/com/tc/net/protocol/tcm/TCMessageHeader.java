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
package com.tc.net.protocol.tcm;

import com.tc.net.protocol.TCNetworkHeader;

public interface TCMessageHeader extends TCNetworkHeader {

  final byte VERSION_1        = (byte) 1;

  public static final int   HEADER_LENGTH    = 2 * 4;

  final int  MIN_LENGTH       = HEADER_LENGTH;
  final int  MAX_LENGTH       = HEADER_LENGTH;
  
  public short getVersion();

  public int getHeaderLength();

  public int getMessageType();

  public int getMessageTypeVersion();

  public void setMessageType(int type);

  public void setMessageTypeVersion(int messageVersion);

}
