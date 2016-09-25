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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.StreamBufferingEncoder.Http2ChannelClosedException;
import io.netty.handler.codec.http2.StreamBufferingEncoder.Http2GoAwayException;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent;
import io.netty.util.ReferenceCountUtil;

import io.netty.util.internal.UnstableApi;

/**
 * <p><em>This API is very immature.</em> The Http2Connection-based API is currently preferred over this API.
 * This API is targeted to eventually replace or reduce the need for the {@link Http2ConnectionHandler} API.
 *
 * <p>A HTTP/2 handler that maps HTTP/2 frames to {@link Http2Frame} objects and vice versa. For every incoming HTTP/2
 * frame, a {@link Http2Frame} object is created and propagated via {@link #channelRead}. Outbound {@link Http2Frame}
 * objects received via {@link #write} are converted to the HTTP/2 wire format. HTTP/2 frames specific to a stream
 * implement the {@link Http2StreamFrame} interface. The {@link Http2FrameCodec} is instantiated using the
 * {@link Http2FrameCodecBuilder}.
 *
 * <h3>Stream Lifecycle</h3>
 *
 * The frame codec delivers and writes frames for active streams. An active stream is closed when either side sends a
 * {@code RST_STREAM} frame or both sides send a frame with the {@code END_STREAM} flag set. The frame codec does not
 * offer help in maintaining additional state for active streams. A user can either choose to implement this himself,
 * or have his handler derive from the {@link Http2ManagedStreamStateHandler}.
 *
 * <h3>Flow control</h3>
 *
 * The frame codec automatically increments stream and connection flow control windows. It's possible to customize
 * when flow control windows are updated via {@link Http2FrameCodecBuilder#windowUpdateRatio(float)}.
 *
 * <p>Incoming flow controlled frames need to be consumed by writing a {@link Http2WindowUpdateFrame} with the consumed
 * number of bytes and the corresponding stream identifier set to the frame codec.
 *
 * <p>The local stream-level flow control window can be changed by writing a {@link Http2SettingsFrame} with the
 * {@link Http2Settings#initialWindowSize()} set to the targeted value.
 *
 * <p>The connection-level flow control window can be changed by writing a {@link Http2WindowUpdateFrame} with the
 * desired window size <em>increment</em> in bytes and the stream identifier set to {@code 0}. By default the initial
 * connection-level flow control window is the same as initial stream-level flow control window.
 *
 * <h3>Opening Outbound Streams</h3>
 *
 * Opening an outbound/local stream works by first write a {@link Http2HeadersFrame} with no stream identifier set (
 * such that {@link Http2HeadersFrame#hasStreamId()} returns false). If opening the stream has been successful, the
 * frame codec responds with a {@link Http2OutboundStreamActiveEvent} that contains the stream's new identifier as
 * well as the <em>same</em> {@link Http2HeadersFrame} object that opened the stream (such that {@code ==} comparison
 * returns {@code true}).
 *
 * <pre>
 * {@link Http2FrameCodec}                                                                            YourChannelHandler
 *        +                                                                                               +
 *        |         Http2HeadersFrame(streamId=-1)                                                        |
 *        <-----------------------------------------------------------------------------------------------+
 *        |                                                                                               |
 *        |         Http2OutboundStreamActiveEvent(streamId=2, headers=Http2HeadersFrame(streamId=-1))    |
 *        +----------------------------------------------------------------------------------------------->
 *        |                                                                                               |
 *        +                                                                                               +
 * </pre>
 *
 * <p>If a new stream cannot be created due to stream id exhaustion of the endpoint, the {@link ChannelPromise} of the
 * HEADERS frame will fail with a {@link Http2NoMoreStreamIdsException}.
 *
 * <p>The HTTP/2 standard allows for an endpoint to limit the maximum number of concurrently active streams via the
 * {@code SETTINGS_MAX_CONCURRENT_STREAMS} setting. When this limit is reached, no new streams can be created. However,
 * the {@link Http2FrameCodec} can be build with {@link Http2FrameCodecBuilder#bufferOutgoingStreams} enabled, in which
 * case a new stream and its associated frames will be buffered until either the limit is increased or an active
 * stream is closed. It's, however, possible that a buffered stream will never become active. That is, the channel might
 * get closed or a GO_AWAY frame might be received. In the first case, all writes of buffered streams will fail with a
 * {@link Http2ChannelClosedException}. In the second case, all writes of buffered streams with an identifier less than
 * the last stream identifier of the GO_AWAY frame will fail with a {@link Http2GoAwayException}.
 *
 * <h3>Error Handling</h3>
 *
 * Exceptions and errors are propagated via {@link ChannelInboundHandler#exceptionCaught}. An exception may be
 * generic, apply to the HTTP/2 connection or even a specific HTTP/2 stream. It's possible and encouraged to handle
 * those cases differently:
 *
 * <pre>
 * class MyChannelHandler extends ChannelDuplexHandler {
 *     {@literal @}Override
 *     public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
 *         Http2Exception http2Exception = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
 *         if (isStreamError(http2Ex)) {
 *             StreamException streamException = (StreamException) http2Exception;
 *             // Handle stream specific error
 *         } else if (http2Ex instanceof CompositeStreamException) {
 *             // Multiple exceptions for (different) streams wrapped in one exception.
 *             CompositeStreamException compositeException = (CompositeStreamException) http2Ex;
 *             for (StreamException streamException : compositeException) {
 *                 // Handle stream specific error
 *             }
 *         } else if (http2Exception != null) {
 *             // Handle HTTP/2 connection specific error
 *         } else {
 *             // None HTTP/2 related error
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>HTTP Upgrade</h3>
 *
 * Server-side HTTP to HTTP/2 upgrade is supported in conjunction with {@link Http2ServerUpgradeCodec}; the necessary
 * HTTP-to-HTTP/2 conversion is performed automatically.
 */
@UnstableApi
public class Http2FrameCodec extends ChannelDuplexHandler {

    private final Http2ConnectionHandler http2Handler;
    private final boolean server;
    // Used to adjust flow control window on channel active. Set to null afterwards.
    private Integer initialLocalConnectionWindow;

    private ChannelHandlerContext ctx;
    private ChannelHandlerContext http2HandlerCtx;

    /**
     * Create a new handler. Use {@link Http2FrameCodecBuilder}.
     */
    Http2FrameCodec(Http2ConnectionEncoder encoder, Http2ConnectionDecoder decoder, Http2Settings initialSettings,
                    long gracefulShutdownTimeoutMillis) {
        decoder.frameListener(new FrameListener());
        http2Handler = new InternalHttp2ConnectionHandler(decoder, encoder, initialSettings);
        http2Handler.connection().addListener(new ConnectionListener());
        http2Handler.gracefulShutdownTimeoutMillis(gracefulShutdownTimeoutMillis);
        server = http2Handler.connection().isServer();
        initialLocalConnectionWindow = initialSettings.initialWindowSize();
    }

    Http2ConnectionHandler connectionHandler() {
        return http2Handler;
    }

    /**
     * Load any dependencies.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        ctx.pipeline().addBefore(ctx.executor(), ctx.name(), null, http2Handler);
        http2HandlerCtx = ctx.pipeline().context(http2Handler);
    }

    /**
     * Clean up any dependencies.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().remove(http2Handler);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        sendInitialConnectionWindow();
        super.channelActive(ctx);
    }

    private void sendInitialConnectionWindow() throws Http2Exception {
        if (initialLocalConnectionWindow != null) {
            Http2Stream connectionStream = http2Handler.connection().connectionStream();
            int currentSize = connection().local().flowController().windowSize(connectionStream);
            int delta = initialLocalConnectionWindow - currentSize;
            http2Handler.decoder().flowController().incrementWindowSize(connectionStream, delta);
            initialLocalConnectionWindow = null;
            ctx.flush();
        }
    }

    private Http2Connection connection() {
        return http2Handler.connection();
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
        ctx.fireUserEventTriggered(upgrade.retain());
        try {
            Http2Stream stream = http2Handler.connection().stream(Http2CodecUtil.HTTP_UPGRADE_STREAM_ID);
            // TODO: improve handler/stream lifecycle so that stream isn't active before handler added.
            // The stream was already made active, but ctx may have been null so it wasn't initialized.
            // https://github.com/netty/netty/issues/4942
            new ConnectionListener().onStreamActive(stream);
            upgrade.upgradeRequest().headers().setInt(
                    HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), Http2CodecUtil.HTTP_UPGRADE_STREAM_ID);
            new InboundHttpToHttp2Adapter(http2Handler.connection(), http2Handler.decoder().frameListener())
                    .channelRead(ctx, upgrade.upgradeRequest().retain());
        } finally {
            upgrade.release();
        }
    }

    /**
     * Processes all {@link Http2Frame}s. {@link Http2StreamFrame}s may only originate in child
     * streams.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        try {
            if (msg instanceof Http2WindowUpdateFrame) {
                Http2WindowUpdateFrame frame = (Http2WindowUpdateFrame) msg;
                writeWindowUpdate(frame.getStreamId(), frame.windowSizeIncrement(), promise);
            } else if (msg instanceof Http2StreamFrame) {
                writeStreamFrame((Http2StreamFrame) msg, promise);
            } else if (msg instanceof Http2SettingsFrame) {
                writeSettingsFrame((Http2SettingsFrame) msg, promise);
            } else if (msg instanceof Http2GoAwayFrame) {
                writeGoAwayFrame((Http2GoAwayFrame) msg, promise);
            } else {
                throw new UnsupportedMessageTypeException(msg);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void writeWindowUpdate(int streamId, int bytes, ChannelPromise promise) {
        try {
            if (streamId == 0) {
                increaseInitialConnectionWindow(bytes);
            } else {
                consumeBytes(streamId, bytes);
            }
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    private void increaseInitialConnectionWindow(int deltaBytes) throws Http2Exception {
        Http2LocalFlowController localFlow = connection().local().flowController();
        int targetConnectionWindow = localFlow.initialWindowSize() + deltaBytes;
        localFlow.incrementWindowSize(connection().connectionStream(), deltaBytes);
        localFlow.initialWindowSize(targetConnectionWindow);
    }

    private void consumeBytes(int streamId, int bytes) throws Http2Exception {
        Http2Stream stream = http2Handler.connection().stream(streamId);
        http2Handler.connection().local().flowController()
                    .consumeBytes(stream, bytes);
    }

    private void writeSettingsFrame(Http2SettingsFrame frame, ChannelPromise promise) {
        http2Handler.encoder().writeSettings(http2HandlerCtx, frame.settings(), promise);
    }

    private void writeGoAwayFrame(Http2GoAwayFrame frame, ChannelPromise promise) {
        if (frame.lastStreamId() > -1) {
            throw new IllegalArgumentException("Last stream id must not be set on GOAWAY frame");
        }

        int lastStreamCreated = http2Handler.connection().remote().lastStreamCreated();
        int lastStreamId = lastStreamCreated + frame.extraStreamIds() * 2;
        // Check if the computation overflowed.
        if (lastStreamId < lastStreamCreated) {
            lastStreamId = Integer.MAX_VALUE;
        }
        http2Handler.goAway(
                http2HandlerCtx, lastStreamId, frame.errorCode(), frame.content().retain(), promise);
    }

    private void writeStreamFrame(Http2StreamFrame frame, ChannelPromise promise) {
        if (frame instanceof Http2DataFrame) {
            Http2DataFrame dataFrame = (Http2DataFrame) frame;
            http2Handler.encoder().writeData(http2HandlerCtx, frame.getStreamId(), dataFrame.content().retain(),
                                             dataFrame.padding(), dataFrame.isEndStream(), promise);
        } else if (frame instanceof Http2HeadersFrame) {
            writeHeadersFrame((Http2HeadersFrame) frame, promise);
        } else if (frame instanceof Http2ResetFrame) {
            Http2ResetFrame rstFrame = (Http2ResetFrame) frame;
            http2Handler.resetStream(http2HandlerCtx, frame.getStreamId(), rstFrame.errorCode(), promise);
        } else {
            throw new UnsupportedMessageTypeException(frame);
        }
    }

    private void writeHeadersFrame(final Http2HeadersFrame headersFrame, ChannelPromise promise) {
        final int streamId;
        if (headersFrame.hasStreamId()) {
            streamId = headersFrame.getStreamId();
        } else {
            Http2Connection connection = http2Handler.connection();
            streamId = connection.local().incrementAndGetNextStreamId();
            if (streamId < 0) {
                promise.setFailure(new Http2NoMoreStreamIdsException());
                return;
            }
            headersFrame.setStreamId(streamId);
            promise.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        ctx.fireUserEventTriggered(
                                new Http2OutboundStreamActiveEvent(streamId, headersFrame));
                    }
                }
            });
        }
        http2Handler.encoder().writeHeaders(http2HandlerCtx, streamId, headersFrame.headers(), headersFrame.padding(),
                                            headersFrame.isEndStream(), promise);
    }

    private final class ConnectionListener extends Http2ConnectionAdapter {
        @Override
        public void onGoAwayReceived(final int lastStreamId, long errorCode, ByteBuf debugData) {
            ctx.fireChannelRead(new DefaultHttp2GoAwayFrame(lastStreamId, errorCode, debugData.retain()));
        }
    }

    private static final class InternalHttp2ConnectionHandler extends Http2ConnectionHandler {
        InternalHttp2ConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                       Http2Settings initialSettings) {
            super(decoder, encoder, initialSettings);
        }

        @Override
        protected void onConnectionError(ChannelHandlerContext ctx, Throwable cause, Http2Exception http2Ex) {
            try {
                ctx.fireExceptionCaught(cause);
            } finally {
                super.onConnectionError(ctx, cause, http2Ex);
            }
        }

        @Override
        protected void onStreamError(ChannelHandlerContext ctx, Throwable cause,
                                     Http2Exception.StreamException http2Ex) {
            try {
                ctx.fireExceptionCaught(cause);
            } finally {
                super.onStreamError(ctx, cause, http2Ex);
            }
        }
    }

    private final class FrameListener extends Http2FrameAdapter {
        @Override
        public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
            ctx.fireChannelRead(new DefaultHttp2SettingsFrame(settings));
        }

        @Override
        public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) {
            ctx.fireChannelRead(new DefaultHttp2PingFrame(data, false));
        }

        @Override
        public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) {
            ctx.fireChannelRead(new DefaultHttp2PingFrame(data, true));
        }

        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
            Http2ResetFrame rstFrame = new DefaultHttp2ResetFrame(errorCode);
            rstFrame.setStreamId(streamId);
            ctx.fireChannelRead(rstFrame);
        }

        @Override
        public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
            if (streamId == 0) {
                // Ignore connection window updates.
                return;
            }
            ctx.fireChannelRead(new DefaultHttp2WindowUpdateFrame(windowSizeIncrement).setStreamId(streamId));
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
            Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers, endOfStream, padding);
            headersFrame.setStreamId(streamId);
            ctx.fireChannelRead(headersFrame);
        }

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
                              boolean endOfStream) {
            Http2DataFrame dataFrame = new DefaultHttp2DataFrame(data.retain(), endOfStream, padding);
            dataFrame.setStreamId(streamId);
            ctx.fireChannelRead(dataFrame);

            // We return the bytes in consumeBytes() once the stream channel consumed the bytes.
            return 0;
        }
    }
}
