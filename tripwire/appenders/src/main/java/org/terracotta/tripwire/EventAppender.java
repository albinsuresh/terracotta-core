/*
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.tripwire;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class EventAppender extends AppenderBase<ILoggingEvent> {
  
  private final static boolean ENABLED;
  
  static {
    boolean check = false;
    try {
      Class.forName("jdk.jfr.Event");
      check = true && !Boolean.getBoolean("tripwire.logging.disable");
    } catch (ClassNotFoundException cnf) {
    }
    ENABLED = check;
  }
  
  public EventAppender() {
  }

  @Override
  protected void append(ILoggingEvent e) {
    if (ENABLED) {
      new LogEvent(e.getLoggerName(), e.getLevel().toString(), e.getFormattedMessage()).commit();
    }
  }
  
  public static boolean isEnabled() {
    return ENABLED;
  }
}
