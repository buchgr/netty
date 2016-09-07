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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.UnstableApi;

/**
 * An HTTP/2 handler that maps HTTP/2 frames to {@link Http2Frame} objects and vice versa. For every incoming HTTP/2
 * frame a {@link Http2Frame} object is created and propagated via {@link #channelRead}. Outgoing {@link Http2Frame}
 * objects received via {@link #write} are converted to the HTTP/2 wire format.
 *
 * <p>A change in stream state is propagated through the channel pipeline as a user event via
 * {@link Http2StreamStateEvent} objects. When a HTTP/2 stream first becomes active a {@link Http2StreamActiveEvent}
 * and when it gets closed a {@link Http2StreamClosedEvent} is emitted.
 *
 * <p>Server-side HTTP to HTTP/2 upgrade is supported in conjunction with {@link Http2ServerUpgradeCodec}; the necessary
 * HTTP-to-HTTP/2 conversion is performed automatically.
 *
 * <p><em>This API is very immature.</em> The Http2Connection-based API is currently preferred over
 * this API. This API is targeted to eventually replace or reduce the need for the Http2Connection-based API.
 */
@UnstableApi
public final class Http2FrameCodec extends Http2ConnectionHandler {

    /**
     * Construct a new handler.
     *
     * @param server {@code true} this is a server
     */
    public Http2FrameCodec(boolean server) {
        this(server, new DefaultHttp2FrameWriter(), new DefaultHttp2FrameReader(), new Http2Settings());
    }

    /**
     * Construct a new handler.
     *
     * @param server {@code true} this is a server
     * @param writer the {@link Http2FrameWriter} to use
     * @param reader the {@link Http2FrameReader} to use
     */
    public Http2FrameCodec(boolean server, Http2FrameWriter writer, Http2FrameReader reader,
                           Http2Settings initialSettings) {
        super(server, writer, reader, initialSettings);
    }

    @Override
    protected void onStreamError(ChannelHandlerContext ctx, Throwable cause, Http2Exception.StreamException http2Ex) {
        try {
            if (connection().stream(http2Ex.streamId()) != null) {
                ctx.fireExceptionCaught(http2Ex);
            }
        } finally {
            super.onStreamError(ctx, cause, http2Ex);
        }
    }

    /**
     * Load any dependencies.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        connection().addListener(new ConnectionListener(ctx));
        decoder().frameListener(new FrameListener());
    }

    /**
     * Handles the cleartext HTTP upgrade event. If an upgrade occurred, sends a simple response via
     * HTTP/2 on stream 1 (the stream specifically reserved for cleartext HTTP upgrade).
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (!(evt instanceof UpgradeEvent)) {
            super.userEventTriggered(ctx, evt);
            return;
        }

        UpgradeEvent upgrade = (UpgradeEvent) evt;
        FullHttpRequest request = upgrade.upgradeRequest().retainedDuplicate();
        ctx.fireUserEventTriggered(upgrade);
        try {
            Http2Stream stream = connection().stream(Http2CodecUtil.HTTP_UPGRADE_STREAM_ID);
            // TODO: improve handler/stream lifecycle so that stream isn't active before handler added.
            // The stream was already made active, but ctx may have been null so it wasn't initialized.
            // https://github.com/netty/netty/issues/4942
            fireOnStreamActive(ctx, stream);
            request.headers().setInt(
                    HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), Http2CodecUtil.HTTP_UPGRADE_STREAM_ID);
            InboundHttpToHttp2Adapter.convertAndDispatch(ctx, connection(), decoder().frameListener(), request);
        } finally {
            upgrade.release();
        }
    }

    /**
     * Processes all {@link Http2Frame}s. {@link Http2StreamFrame}s may only originate in child
     * streams.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof Http2Frame) {
            System.err.println("HERE");

            if (msg instanceof Http2StreamFrame) {
                writeStreamFrame(ctx, (Http2StreamFrame) msg, promise);
            } else if (msg instanceof Http2GoAwayFrame) {
                writeGoAwayFrame(ctx, (Http2GoAwayFrame) msg, promise);
            } else {
                ReferenceCountUtil.release(msg);
                throw new UnsupportedMessageTypeException(msg);
            }
        } else {
            super.write(ctx, msg, promise);
        }
    }

    private void consumeBytes(int streamId, int bytes, ChannelPromise promise) {
        try {
            Http2Stream stream = connection().stream(streamId);
            connection().local().flowController().consumeBytes(stream, bytes);
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    private void writeGoAwayFrame(ChannelHandlerContext ctx, Http2GoAwayFrame frame, ChannelPromise promise) {
        if (frame.lastStreamId() > -1) {
            throw new IllegalArgumentException("Last stream id must not be set on GOAWAY frame");
        }

        int lastStreamCreated = connection().remote().lastStreamCreated();
        int lastStreamId = lastStreamCreated + frame.extraStreamIds() * 2;
        // Check if the computation overflowed.
        if (lastStreamId < lastStreamCreated) {
            lastStreamId = Integer.MAX_VALUE;
        }
        goAway(ctx, lastStreamId, frame.errorCode(), frame.content(), promise);
    }

    private void writeStreamFrame(ChannelHandlerContext ctx, Http2StreamFrame frame, ChannelPromise promise) {
        if (frame instanceof Http2HeadersFrame) {
            Http2HeadersFrame headerFrame = (Http2HeadersFrame) frame;
            encoder().writeHeaders(ctx, frame.streamId(), headerFrame.headers(), headerFrame.padding(),
                    headerFrame.isEndStream(), promise);
        } else if (frame instanceof Http2DataFrame) {
            Http2DataFrame dataFrame = (Http2DataFrame) frame;
            encoder().writeData(ctx, frame.streamId(), dataFrame.content(),
                    dataFrame.padding(), dataFrame.isEndStream(), promise);
        } else if (frame instanceof Http2ResetFrame) {
            resetStream(ctx, frame.streamId(), ((Http2ResetFrame) frame).errorCode(), promise);
        } else if (frame instanceof Http2WindowUpdateFrame) {
            consumeBytes(frame.streamId(), ((Http2WindowUpdateFrame) frame).windowSizeIncrement(), promise);
        } else {
            throw new UnsupportedMessageTypeException(frame);
        }
    }

    private static void fireOnStreamActive(ChannelHandlerContext ctx, Http2Stream stream) {
        ctx.fireUserEventTriggered(new Http2StreamActiveEvent(stream.id()));
    }

    private static final class ConnectionListener extends Http2ConnectionAdapter {
        private final ChannelHandlerContext ctx;

        ConnectionListener(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onStreamActive(Http2Stream stream) {
            fireOnStreamActive(ctx, stream);
        }

        @Override
        public void onStreamClosed(Http2Stream stream) {
            ctx.fireUserEventTriggered(new Http2StreamClosedEvent(stream.id()));
        }

        @Override
        public void onGoAwayReceived(final int lastStreamId, long errorCode, ByteBuf debugData) {
            ctx.fireChannelRead(new DefaultHttp2GoAwayFrame(lastStreamId, errorCode, debugData.retain()));
        }
    }

    private static final class FrameListener extends Http2FrameAdapter {
        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
            ctx.fireChannelRead(new DefaultHttp2ResetFrame(errorCode).setStreamId(streamId));
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                                  Http2Headers headers, int streamDependency, short weight, boolean
                                          exclusive, int padding, boolean endStream) {
            onHeadersRead(ctx, streamId, headers, padding, endStream);
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                                  int padding, boolean endOfStream) {
            ctx.fireChannelRead(new DefaultHttp2HeadersFrame(headers, endOfStream, padding).setStreamId(streamId));
        }

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
                              boolean endOfStream) {
            int bytes = data.readableBytes() + padding;
            ctx.fireChannelRead(new DefaultHttp2DataFrame(data.retain(), endOfStream, padding).setStreamId(streamId));

            // We return the bytes in bytesConsumed() once the stream channel consumed the bytes.
            return 0;
        }
    }
}
