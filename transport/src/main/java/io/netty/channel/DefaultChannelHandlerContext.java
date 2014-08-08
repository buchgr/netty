/*
* Copyright 2014 The Netty Project
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
package io.netty.channel;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.PausableEventExecutor;

final class DefaultChannelHandlerContext extends AbstractChannelHandlerContext {
    private final ChannelHandler handler;
    private volatile PausableChannelEventExecutor wrapped;

    DefaultChannelHandlerContext(
            DefaultChannelPipeline pipeline, ChannelHandlerInvoker invoker, String name, ChannelHandler handler) {
        super(pipeline, invoker, name, skipFlags(checkNull(handler)));
        this.handler = handler;
    }

    private static ChannelHandler checkNull(ChannelHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        return handler;
    }

    @Override
    public ChannelHandler handler() {
        return handler;
    }

    @Override
    public EventExecutor executor() {
        // If the ChannelHandler does not have its own
        // invoker/eventloop we can reuse the wrapped
        // eventloop of the channel.
        if (invoker == null) {
            return channel().eventLoop();
        } else {
            // No need for thread safety, because a ChannelHandlerContext is
            // local to a specific Channel and ChannelHandler.
            PausableChannelEventExecutor eventExecutor = this.wrapped;
            if (eventExecutor == null) {
                wrapped = eventExecutor = new PausableChannelEventExecutorImpl();
            }
            return eventExecutor;
        }
    }

    @Override
    public ChannelHandlerInvoker invoker() {
        if (invoker == null) {
            return channel().eventLoop().asInvoker();
        } else {
            // No need for thread safety, because a ChannelHandlerContext is
            // local to a specific Channel and ChannelHandler.
            PausableChannelEventExecutor eventExecutor = this.wrapped;
            if (eventExecutor == null) {
                wrapped = eventExecutor = new PausableChannelEventExecutorImpl();
            }
            return eventExecutor;
        }
    }

    private final class PausableChannelEventExecutorImpl extends PausableChannelEventExecutor {

        @Override
        public EventExecutor unwrap() {
            if (unwrapInvoker() == null) {
                return channel().unsafe().invoker().executor();
            }
            return unwrapInvoker().executor();
        }

        @Override
        public void rejectNewTasks() {
            ((PausableEventExecutor) channel().eventLoop()).rejectNewTasks();
        }

        @Override
        public void acceptNewTasks() {
            ((PausableEventExecutor) channel().eventLoop()).acceptNewTasks();
        }

        @Override
        public boolean isAcceptingNewTasks() {
            return ((PausableEventExecutor) channel().eventLoop()).isAcceptingNewTasks();
        }

        @Override
        Channel channel() {
            return DefaultChannelHandlerContext.super.channel();
        }

        @Override
        ChannelHandlerInvoker unwrapInvoker() {
            // return the unwrapped invoker
            return DefaultChannelHandlerContext.this.invoker;
        }
    }
}
