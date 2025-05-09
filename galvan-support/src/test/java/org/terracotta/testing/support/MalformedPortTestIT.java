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
package org.terracotta.testing.support;

import org.junit.Assert;
import org.terracotta.connection.ConnectionFactory;
import org.terracotta.passthrough.IClientTestEnvironment;
import org.terracotta.passthrough.IClusterControl;

import java.net.URI;
import java.util.Properties;

public class MalformedPortTestIT extends MultiProcessGalvanTest {
  @Override
  public int getClientsToStart() {
    return 1;
  }

  @Override
  public void runTest(IClientTestEnvironment iClientTestEnvironment, IClusterControl iClusterControl) throws Throwable {
    URI connectionUri = URI.create(iClientTestEnvironment.getClusterUri());
    try {
      ConnectionFactory.connect(URI.create(connectionUri.getScheme() + "://" + connectionUri.getHost() + ":1234xx"),
                                new Properties());
      Assert.fail("connect should fail as port in the given uri is malformed");
    } catch (IllegalArgumentException expected) {}
  }
}