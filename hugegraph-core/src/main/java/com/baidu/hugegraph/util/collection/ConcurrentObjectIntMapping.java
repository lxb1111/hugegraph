/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.util.collection;

import com.baidu.hugegraph.perf.PerfUtil.Watched;

public class ConcurrentObjectIntMapping<V> implements ObjectIntMapping<V> {

    private final SingleThreadObjectIntMapping<V> objectIntMapping;

    public ConcurrentObjectIntMapping() {
        this.objectIntMapping = new SingleThreadObjectIntMapping<>();
    }

    @Override
    @Watched
    public synchronized int object2Code(Object object) {
        return this.objectIntMapping.object2Code(object);
    }

    @Override
    @Watched
    public synchronized V code2Object(int code) {
        return this.objectIntMapping.code2Object(code);
    }

    @Override
    public synchronized void clear() {
        this.objectIntMapping.clear();
    }

    @Override
    public synchronized String toString() {
        return this.objectIntMapping.toString();
    }
}
