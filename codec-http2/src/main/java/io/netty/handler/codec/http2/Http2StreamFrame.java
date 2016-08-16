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

import io.netty.util.internal.UnstableApi;

/**
 * A frame whose meaning <em>may</em> apply to a particular stream, instead of the entire
 * connection. It is still possible for this frame type to apply to the entire connection. In such
 * cases, the {@link #getStreamId()} must return {@code 0}. If the frame applies to a stream, the
 * {@link #getStreamId()} must be greater than zero.
 */
@UnstableApi
public interface Http2StreamFrame extends Http2Frame {

    /**
     * Sets the identifier of the stream this frame applies to.
     *
     * @return {@code this}
     */
    Http2StreamFrame setStreamId(int streamId);

    /**
     * The identifier of the stream this frame applies to.
     *
     * @return {@code 0} if the frame applies to the entire connection, a value greater than {@code 0} if the frame
     * applies to a particular stream, or a value less than {@code 0} if the frame has yet to be associated with
     * the connection or a stream.
     */
    int getStreamId();

    boolean hasStreamId();
}
