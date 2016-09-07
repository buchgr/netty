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

package io.netty.example.http2.helloworld.message.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

public class HelloWorldHttp2Handler extends ChannelInboundHandlerAdapter {

    static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Hello World", CharsetUtil.UTF_8));

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame) {
            onHeadersRead(ctx, (Http2HeadersFrame) msg);
        } else if (msg instanceof Http2DataFrame) {
            onDataRead(ctx, (Http2DataFrame) msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private static void onDataRead(ChannelHandlerContext ctx, Http2DataFrame data) {
        try {
            int consumedBytes = data.padding() + data.content().readableBytes();
            if (consumedBytes > 0) {
                // Tell local flow control that we consumed the bytes.
                ctx.write(new DefaultHttp2WindowUpdateFrame(consumedBytes));
            }
            if (data.isEndStream()) {
                sendResponse(ctx, data.getStreamId(), data.content().retain());
            }
        } finally {
            ReferenceCountUtil.safeRelease(data);
        }
    }

    private static void onHeadersRead(ChannelHandlerContext ctx, Http2HeadersFrame headers) {
        if (headers.isEndStream()) {
            ByteBuf content = ctx.alloc().buffer(RESPONSE_BYTES.readableBytes() + 13);
            content.writeBytes(RESPONSE_BYTES, 0, RESPONSE_BYTES.readableBytes());
            ByteBufUtil.writeAscii(content, " - via HTTP/2");
            sendResponse(ctx, headers.getStreamId(), content);
        }
    }

    /**
     * Sends a "Hello World" DATA frame to the client.
     */
    private static void sendResponse(ChannelHandlerContext ctx, int streamId, ByteBuf payload) {
        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
        ctx.write(new DefaultHttp2HeadersFrame(headers).setStreamId(streamId));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(payload, true).setStreamId(streamId));
    }
}
