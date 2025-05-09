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
package com.tc.util.runtime;

import com.tc.util.Assert;

public class ThreadDumpUtilJdk16Test extends ThreadDumpUtilTestBase {

  public void testThreadDump() throws Throwable {
    final int numThreads = 10;
    TraceThread[] threads = new TraceThread[numThreads];
    for (int i = 0; i < numThreads; ++i) {
      threads[i] = new TraceThread();
    }
    String dump = getDump(threads);
    
    try {
      assertTrue(dump.contains("- locked"));
      
      Assert.eval("The text \"Full thread dump \" should be present in the thread dump",
                  dump.indexOf("Full thread dump ") >= 0);
      
      // we expect to see all the created threads waiting on a CountDownLatch
      assertEquals(numThreads, countSubstrings(dump, OBSERVER_GATE));
    } catch (Throwable t) {
      System.err.println(dump);
      throw t;
    }
  }

  /**
   * Thread.getId() should be final but it isn't, so subclasses can break the contract.
   * When this happens we need to behave gracefully.  See CDV-1262.
   * We used to use Thread.getId in ThreadDumpUtilJdk16.getThreadDump(). Since Thread.getId
   * method is not final so if any thread override the method we used to get Exception
   * DEV-3897 changed the behavior for ThreadDumpUtilJdk16.getThreadDump and so we get the 
   * correct thread dump even in case when the Thread.getId() method id overridden. 
   */
  public void testBadThreadId() throws Throwable {
    final int numThreads = 10;
    TraceThread[] threads = new TraceThread[numThreads];
    for (int i = 0; i < numThreads; ++i) {
      threads[i] = (i % 2 == 0) ? new TraceThread() : new BadIdThread();
    }
    String dump = getDump(threads);
    
    try {
      Assert.eval("The text \"Full thread dump \" should be present in the thread dump",
                  dump.indexOf("Full thread dump ") >= 0);
      
      // we expect to see all the created threads waiting on a CountDownLatch
      assertEquals(numThreads, countSubstrings(dump, OBSERVER_GATE));
      
      // half the strings should be complaining about unrecognized IDs
      assertEquals(0, countSubstrings(dump, OVERRIDDEN));
    } catch (Throwable t) {
      System.err.println(dump);
      throw t;
    }
  }
}
