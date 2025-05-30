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
package com.tc.async.impl;

import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.Stage;
import com.tc.util.State;
import com.tc.util.concurrent.SetOnceFlag;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 *
 */
public class StageControllerTest {
  
  public StageControllerTest() {
  }
  
  @BeforeClass
  public static void setUpClass() {
  }
  
  @AfterClass
  public static void tearDownClass() {
  }
  
  @Before
  public void setUp() {
  }
  
  @After
  public void tearDown() {
  }
  /**
   * Test of addTriggerToState method, of class StageController.
   */
  @Test
  public void testAddTriggerToState() {
    System.out.println("addTriggerToState");
    
    State init = new State("INIT");
    State test = new State("TeST");
    
    ConfigurationContext cxt = mock(ConfigurationContext.class);
    StageController instance = new StageController(()->cxt);
    final SetOnceFlag didRun = new SetOnceFlag();

    final Stage prestage = mock(Stage.class);
    when(cxt.getStage(eq("PRE"), any(Class.class))).then(new Answer<Stage>() {
      @Override
      public Stage answer(InvocationOnMock invocation) throws Throwable {
        Assert.assertFalse("PRE", didRun.isSet());
        return prestage;
      }
    });

    final Stage poststage = mock(Stage.class);
    when(cxt.getStage(eq("POST"), any(Class.class))).then(new Answer<Stage>() {
      @Override
      public Stage answer(InvocationOnMock invocation) throws Throwable {
        Assert.assertTrue("POST", didRun.isSet());
        return poststage;
      }
    });
    
    final Stage i = mock(Stage.class);
    when(cxt.getStage(eq("INIT"), any(Class.class))).then(new Answer<Stage>() {
      @Override
      public Stage answer(InvocationOnMock invocation) throws Throwable {
        Assert.assertFalse("INIT", didRun.isSet());
        return i;
      }
    });
    
    instance.addStageToState(init, "INIT");
    instance.addStageToState(test, "PRE");
    instance.addTriggerToState(test, s->didRun.set());
    instance.addStageToState(test, "POST");

    instance.transition(init, test);
    Assert.assertTrue(didRun.isSet());
    verify(prestage).start(cxt);
    verify(poststage).start(cxt);
    verify(i).destroy();
  }
  
}
