/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore.utils;

import java.util.ArrayDeque;
import java.util.Deque;

public class StackThreadLocal<T> {

  private final ThreadLocal<Deque<T>> threadLocal = new ThreadLocal<>();

  public void set(T value) {
    Deque<T> stack = threadLocal.get();
    if (stack == null) {
      stack = new ArrayDeque<>();
    }
    stack.push(value);
    threadLocal.set(stack);
  }

  public void unset() {
    Deque<T> stack = threadLocal.get();
    stack.pop();
    if (stack.isEmpty()) {
      threadLocal.remove();
    }
  }
  
  public T get() {
    Deque<T> stack = threadLocal.get();
    if (stack != null) {
      return stack.peek();
    } else {
      throw new IllegalStateException("There is no context to return!");
    }
  }
  
  public boolean isSet() {
    return threadLocal.get() != null;
  }  

}