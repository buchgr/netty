/*
 * Copyright 2016 The Netty Project
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

package io.netty.handler.codec.http2;

import io.netty.channel.ChannelFuture;

/**
 * Foo bar.
 */
public interface Http2Stream2 {

    Http2Stream2 CONNECTION_STREAM = new Http2Stream2() {

        @Override
        public Http2Stream2 id(int id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int id() {
            return 0;
        }

        @Override
        public Http2Stream2 managedState(Object state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object managedState() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture closeFuture() {
            throw new UnsupportedOperationException();
        }
    };

    Http2Stream2 id(int id);

    int id();

    Http2Stream2 managedState(Object state);

    Object managedState();

    ChannelFuture closeFuture();
}
