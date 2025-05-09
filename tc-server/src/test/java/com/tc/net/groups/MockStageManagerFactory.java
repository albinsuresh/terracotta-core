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
package com.tc.net.groups;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.EventHandler;
import com.tc.async.api.EventHandlerException;
import com.tc.async.api.Sink;
import com.tc.async.api.Stage;
import com.tc.async.api.StageManager;
import static java.lang.Thread.State.RUNNABLE;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.Logger;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 *
 */
public class MockStageManagerFactory {
  
  private final ThreadGroup threadGroup;
  private final Logger logging;
  private volatile boolean alive = true;

  public MockStageManagerFactory(Logger logging, ThreadGroup group) {
    this.threadGroup = group;
    this.logging = logging;
  }

  public StageManager createStageManager() throws Exception {
    StageManager stages = mock(StageManager.class);
    ConcurrentHashMap<String, Stage> created = new ConcurrentHashMap<>();
    when(stages.createStage(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt()))
      .then((invoke)->{
        String stageName = invoke.getArguments()[0].toString();
        ExecutorService service = createExecutor(stageName, -1);
        Stage stage = mock(Stage.class);
        Sink sink = mock(Sink.class);
        EventHandler ev = (EventHandler)invoke.getArguments()[2];
        doAnswer((invoke2)->{
          service.submit(()->{
            try {
              ev.handleEvent(invoke2.getArguments()[0]);
            } catch (EventHandlerException e) {
            }
          });
          return null;
        }).when(sink).addToSink(ArgumentMatchers.any());
        
        when(stage.getSink()).thenReturn(sink);
        created.put(stageName, stage);
        return stage;
      });
    
    when(stages.createStage(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyBoolean(), ArgumentMatchers.anyBoolean()))
      .then((invoke)->{
        String stageName = invoke.getArguments()[0].toString();
        int size = (Integer)invoke.getArguments()[4];
        ExecutorService service = createExecutor(stageName, size);
        Stage stage = mock(Stage.class);
        Sink sink = mock(Sink.class);
        EventHandler ev = (EventHandler)invoke.getArguments()[2];
        doAnswer((invoke2)->{
          service.submit(()->{
            try {
              ev.handleEvent(invoke2.getArguments()[0]);
            } catch (EventHandlerException e) {
            }
          });
          return null;
        }).when(sink).addToSink(ArgumentMatchers.any());

        when(stage.getSink()).thenReturn(sink);
        created.put(stageName, stage);
        return stage;
      });
    
    when(stages.getStage(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
        .then((invoke)->{
          return created.get(invoke.getArguments()[0].toString());
        });
    
    Mockito.doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        shutdown();
        return null;
      }
    }).when(stages).stopAll();
    return stages;
  }
  
  private ExecutorService createExecutor(String name, int size) {
    BlockingQueue<Runnable> queue = size <= 0 ? new LinkedBlockingQueue<>() : new ArrayBlockingQueue<>(size);
    new Thread(threadGroup, ()->{ 
      
      while (alive) {
        Runnable next = null;
        try {
          next = queue.take();
        } catch (InterruptedException ie) {
          break;
        } 
        if (next != null) next.run();
      }
      
      queue.clear();
    }, "Stage - " + name).start();
    
    return new AbstractExecutorService() {
      @Override
      public void shutdown() {
       
      }

      @Override
      public List<Runnable> shutdownNow() {
        return Collections.emptyList();
      }

      @Override
      public boolean isShutdown() {
        return !alive;
      }

      @Override
      public boolean isTerminated() {
        return threadGroup.activeCount() == 0;
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return false;
      }

      @Override
      public void execute(Runnable command) {
        try {
          queue.put(command);
        } catch (InterruptedException ie) {
          throw new RuntimeException(ie);
        }
      }
    };
  }
  
  public static <T> EventHandler<T> createEventHandler(Consumer<T> r) {
    return new AbstractEventHandler<T>() {
      @Override
      public void handleEvent(T context) throws EventHandlerException {
        r.accept(context);
      }
    };
  }
  
  public void quietThreads() {
    Thread[] threads = new Thread[250];
    int spins = 0;
    boolean waited = true;
    while (waited && spins++ < 5 && threadGroup.activeCount() > 0) {
      waited = false;
      int count = threadGroup.enumerate(threads);
      for (int x=0;x<count;x++) {
        try {
          if (threads[x].isAlive()) {
          Thread.State state = threads[x].getState();
            if (state == RUNNABLE) {
              Thread.sleep(1000);
              logging.info(threads[x].getName() + " is RUNNABLE, sleeping 1 sec.");
              waited = true;
            } else {
              logging.info(threads[x].getName() + " is " + state);
            }
          }
        } catch (InterruptedException ie) {
          throw new RuntimeException(ie);
        }
      }
    }
  }
  
  public void shutdown() {
    alive = false;
    Thread[] threads = new Thread[250];
    while (threadGroup.activeCount() > 0) {
      int count = threadGroup.enumerate(threads);
      for (int x=0;x<count;x++) {
        try {
          threads[x].interrupt();
          threads[x].join();
        } catch (InterruptedException ie) {
          throw new RuntimeException(ie);
        }
      }
    }
  }
}
