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
package com.tc.test;

import static com.tc.test.ScriptTestUtil.showArguments;
import static com.tc.test.ScriptTestUtil.showEnvironment;
import static com.tc.test.ScriptTestUtil.showProperties;

/**
 * Surrogate class used by script testing as a substitute for the main
 * class called by a script under testing.
 *
 * @see BaseScriptTest
 */
public class SurrogateMain {
  public static void main(String[] args) {
    showArguments(System.out, args);
    showProperties(System.out);
    showEnvironment(System.out);
  }
}
