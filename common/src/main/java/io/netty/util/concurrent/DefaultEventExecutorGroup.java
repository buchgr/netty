/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import java.util.concurrent.Executor;

/**
 * Default implementation of {@link MultithreadEventExecutorGroup} which will use {@link DefaultEventExecutor}
 * instances to handle the tasks.
 */
public class DefaultEventExecutorGroup extends MultithreadEventExecutorGroup {

    /**
     * @see {@link #DefaultEventExecutorGroup(int, Executor)}
     */
    public DefaultEventExecutorGroup(int nEventExecutors) {
        this(nEventExecutors, (Executor) null);
    }

    public DefaultEventExecutorGroup(int nEventExecutors, Executor executor) {
        super(nEventExecutors, executor);
    }

    public DefaultEventExecutorGroup(int nEventExecutors, ExecutorFactory executorFactory) {
        super(nEventExecutors, executorFactory);
    }

    @Override
    protected EventExecutor newChild(Executor executor, Object... args) throws Exception {
        return new DefaultEventExecutor(this, executor);
    }
}
