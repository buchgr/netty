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
 * User event emitted by the {@link Http2FrameCodec} when an outbound/local stream becomes active.
 */
@UnstableApi
public class Http2OutboundStreamActiveEvent {

    private final int streamId;
    private final Http2HeadersFrame headers;

    public Http2OutboundStreamActiveEvent(int streamId, Http2HeadersFrame headers) {
        this.streamId = streamId;
        this.headers = ObjectUtil.checkNotNull(headers, "headers");
    }

    /**
     * This method returns the <em>same</em> {@link Http2HeadersFrame} object as the one that
     * made the stream active.
     */
    public Http2HeadersFrame headers() {
        return headers;
    }

    public int streamId() {
        return streamId;
    }
}
