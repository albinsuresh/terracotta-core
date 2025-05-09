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
package org.terracotta.testing.rules;

import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import org.junit.ClassRule;
import org.junit.Test;
import org.terracotta.connection.Connection;
import org.terracotta.connection.ConnectionException;
import org.terracotta.connection.ConnectionFactory;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 *
 * @author cdennis
 */
public class BasicExternalClusterClassRuleIT {

  @ClassRule
  public static final Cluster CLUSTER = BasicExternalClusterBuilder.newCluster().build();

  @Test
  public void testDirectConnection() throws IOException, ConnectionException {
    Connection connection = CLUSTER.newConnection();
    try {
      //do nothing
    } finally {
      connection.close();
    }
  }

  @Test
  public void testConnectionViaURI() throws IOException, ConnectionException {
    Connection connection = ConnectionFactory.connect(CLUSTER.getConnectionURI(), new Properties());
    try {
      //do nothing
    } finally {
      connection.close();
    }
  }


  @Test
  public void testClusterHostPorts() throws Exception {
    String[] clusterHostPorts = CLUSTER.getClusterHostPorts();
    assertThat(clusterHostPorts.length, is(1));
    URI uri = new URI("tc://" + clusterHostPorts[0]);
    assertThat(uri.getHost(), is("localhost"));
  }

}
