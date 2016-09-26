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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * Foo bar.
 */
public class Http2ChannelDuplexHandler extends ChannelDuplexHandler {

    private Http2FrameCodec frameCodec;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        frameCodec = requireHttp2FrameCodec(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        frameCodec = null;
    }

    public final <V> Http2Stream2<V> newStream() {
        return newStream0();
    }

    protected final <V> void forEachActiveStream(Http2StreamVisitor2<V> streamVisitor) throws Http2Exception {
        forEachActiveStream0(streamVisitor);
    }

    // So that it can be overwritten by tests, without being visible to the public.
    <V> void forEachActiveStream0(Http2StreamVisitor2<V> streamVisitor) throws Http2Exception {
        frameCodec.forEachActiveStream(streamVisitor);
    }

    // So that it can be overwritten by tests, without being visible to the public.
    <V> Http2Stream2<V> newStream0() {
        if (frameCodec == null) {
            throw new IllegalStateException("Frame codec not found. Has the handler been added to a pipeline?");
        }
        return frameCodec.newStream();
    }

    private static Http2FrameCodec requireHttp2FrameCodec(ChannelHandlerContext ctx) {
        ChannelHandlerContext frameCodecCtx = ctx.pipeline().context(Http2FrameCodec.class);
        if (frameCodecCtx == null) {
            throw new IllegalArgumentException(Http2FrameCodec.class.getSimpleName()
                                               + " was not found in the channel pipeline.");
        }
        return (Http2FrameCodec) frameCodecCtx.handler();
    }
}
