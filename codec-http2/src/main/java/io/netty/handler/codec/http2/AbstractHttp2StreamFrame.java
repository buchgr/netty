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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.UnstableApi;

/**
 * Abstract implementation of {@link Http2StreamFrame}.
 */
@UnstableApi
public abstract class AbstractHttp2StreamFrame implements Http2StreamFrame {

    private volatile Http2Stream2<?> stream;

    @Override
    public <V> AbstractHttp2StreamFrame stream(Http2Stream2<V> stream) {
        this.stream = stream;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> Http2Stream2<V> stream() {
        return (Http2Stream2<V>) stream;
    }

    /**
     * Returns {@code true} if {@code o} has equal {@code stream} to this object.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Http2StreamFrame)) {
            return false;
        }
        Http2StreamFrame other = (Http2StreamFrame) o;
        return stream == other.stream();
    }

    @Override
    public int hashCode() {
        return stream.hashCode();
    }
}
