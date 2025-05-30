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
package com.tc.classloader;

import java.util.Set;

/**
 * @author vmad
 */
public class BasicCommonComponentChecker implements CommonComponentChecker {

    private final Set<String> commonClasses;

    public BasicCommonComponentChecker(Set<String> commonClasses) {
        this.commonClasses = commonClasses;
    }

    /**
     *
     * @param clazz to be checked
     * @return true if given class is a common component
     */

    @Override
    public boolean check(Class<?> clazz) {
        return this.commonClasses.contains(clazz.getName());
    }
}
