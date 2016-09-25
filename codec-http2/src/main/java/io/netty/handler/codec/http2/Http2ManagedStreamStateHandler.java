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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Exception.CompositeStreamException;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.util.collection.IntObjectHashMap;

import static io.netty.handler.codec.http2.Http2Exception.isStreamError;

/**
 * Foooooo bar.
 */
public abstract class Http2ManagedStreamStateHandler<T> extends ChannelDuplexHandler {

    public interface StreamVisitor<T> {
        boolean visit(T managedState);
    }

    // Visible for testing
    IntObjectHashMap<StreamInfo<T>> activeStreams;

    /**
     * Whether the channel is a client or server. The handler will auto-detect this based on the first frame sent or
     * received.
     *
     * Possible values:
     *  -1: Not yet detected
     *  0: Server
     *  1: Client
     */
    // Visible for testing
    int endPointMode = -1;

    /**
     * The largest stream identifier created by the remote endpoint. New streams need to have a stream identifier
     * strictly larger than all other stream identifiers. This variable is used as a switch to decide if
     * a stream is new.
     */
    private int largestRemoteStreamIdentifier;

    private ChannelHandlerContext ctx;

    private final ChannelHandler writeHandler = new WriteHandler();

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        activeStreams = new IntObjectHashMap<StreamInfo<T>>();
        endPointMode = -1;
        largestRemoteStreamIdentifier = 0;
        this.ctx = ctx;
        ctx.pipeline().addBefore(ctx.executor(), ctx.name(), null, writeHandler);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        activeStreams = null;
        endPointMode = -1;
        largestRemoteStreamIdentifier = 0;
        this.ctx = ctx;
        ctx.pipeline().remove(writeHandler);
    }

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http2Frame)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (!(msg instanceof Http2StreamFrame)) {
            channelRead(ctx, (Http2Frame) msg);
            return;
        }

        final Http2StreamFrame streamFrame = (Http2StreamFrame) msg;
        final int streamId = streamFrame.getStreamId();

        if (isNewRemoteStream(streamId)) {
            createNewRemoteStream(streamId, streamFrame);
            return;
        }

        final StreamInfo<T> streamInfo = requireStreamInfo(streamId);

        if (streamFrame instanceof Http2ResetFrame) {
            closeStream(streamInfo, streamId);
            return;
        }

        try {
            channelRead(ctx, streamFrame, streamInfo.managedState);
        } finally {
            streamInfo.endOfStreamReceived |= endOfStreamSet(streamFrame);
            tryCloseStream(streamInfo, streamId);
        }
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        final Http2Exception http2Ex = Http2CodecUtil.getEmbeddedHttp2Exception(cause);

        if (isStreamError(http2Ex)) {
            final StreamException streamException = (StreamException) http2Ex;
            final StreamInfo<T> streamInfo = activeStreams.get(streamException.streamId());

            if (streamInfo != null) {
                onStreamError(ctx, cause, streamException, streamInfo.managedState);
            }
        } else if (http2Ex instanceof CompositeStreamException) {
            // Multiple exceptions for (different) streams wrapped in one exception.
            final CompositeStreamException compositeException = (CompositeStreamException) http2Ex;

            for (StreamException streamException : compositeException) {
                StreamInfo<T> streamInfo = activeStreams.get(streamException.streamId());

                if (streamInfo != null) {
                    onStreamError(ctx, cause, streamException, streamInfo.managedState);
                }
            }
        } else {
            onConnectionError(ctx, cause, http2Ex);
        }
    }

    /**
     * Called for stream frames.
     *
     * @param ctx
     * @param frame
     * @param managedState
     * @throws Exception
     */
    protected abstract void channelRead(ChannelHandlerContext ctx, Http2StreamFrame frame, T managedState)
            throws Exception;

    /**
     * Called for non-stream frames.
     *
     * @param ctx
     * @param frame
     * @throws Exception
     */
    protected abstract void channelRead(ChannelHandlerContext ctx, Http2Frame frame) throws Exception;

    /**
     * Called for active streams.
     *
     * @param streamId
     * @param firstHeaders
     * @return
     * @throws Exception
     */
    protected abstract T onStreamActive(ChannelHandlerContext ctx, int streamId, Http2HeadersFrame firstHeaders)
            throws Exception;

    /**
     * Called when a stream is closed.
     *
     * @param managedState
     */
    protected abstract void onStreamClosed(ChannelHandlerContext ctx, int streamId, T managedState);

    /**
     * Called on stream error.
     *
     * @param cause
     * @param streamException
     * @param managedState
     */
    protected void onStreamError(ChannelHandlerContext ctx, Throwable cause, StreamException streamException,
                                 T managedState) {
        ctx.fireExceptionCaught(cause);
    }

    /**
     * Called on connection error.
     *
     * @param cause
     * @param http2Ex
     */
    protected void onConnectionError(ChannelHandlerContext ctx, Throwable cause, Http2Exception http2Ex) {
        ctx.fireExceptionCaught(cause);
    }

    protected T managedState(int streamId) {
        final StreamInfo<T> streamInfo = activeStreams.get(streamId);

        if (streamInfo != null) {
            return streamInfo.managedState;
        }

        return null;
    }

    protected final void forEachActiveStream(StreamVisitor<T> streamVisitor) {
        for (StreamInfo<T> streamInfo : activeStreams.values()) {
            if (!streamVisitor.visit(streamInfo.managedState)) {
                break;
            }
        }
    }

    private boolean isNewRemoteStream(int streamId) {
        return isRemoteStream(streamId) && streamId > largestRemoteStreamIdentifier;
    }

    private void createNewRemoteStream(int streamId, Http2StreamFrame frame) {
        if (!(frame instanceof Http2HeadersFrame)) {
            throw new IllegalStateException("The first frame must be a HEADERS frame. Was: " + frame.name());
        }

        try {
            final T managedState = onStreamActive(ctx, streamId, (Http2HeadersFrame) frame);
            final StreamInfo<T> streamInfo = new StreamInfo<T>(managedState);

            activeStreams.put(streamId, streamInfo);

            largestRemoteStreamIdentifier = streamId;
        } catch (Throwable t) {
            resetStreamAndClose(ctx, streamId, t);

            ctx.fireExceptionCaught(t);
        }
    }

    private StreamInfo<T> requireStreamInfo(int streamId) {
        StreamInfo<T> info = activeStreams.get(streamId);

        if (info == null) {
            throw new IllegalStateException("Stream with identifier " + streamId + " expected to be an active stream.");
        }

        return info;
    }

    private void tryCloseStream(StreamInfo<T> streamInfo, int streamId) {
        if (streamInfo != null
            && streamInfo.endOfStreamReceived
            && streamInfo.endOfStreamSent) {
            closeStream(streamInfo, streamId);
        }
    }

    private static boolean endOfStreamSet(Object streamFrame) {
        return streamFrame instanceof Http2HeadersFrame && ((Http2HeadersFrame) streamFrame).isEndStream()
               || streamFrame instanceof Http2DataFrame && ((Http2DataFrame) streamFrame).isEndStream();
    }

    private void closeStream(StreamInfo<T> streamInfo, int streamId) {
        try {
            onStreamClosed(ctx, streamId, streamInfo.managedState);
        } catch (Throwable t) {
            // Intentionally left blank.
        } finally {
            activeStreams.remove(streamId);
        }
    }

    private boolean isRemoteStream(int streamId) {
        final int isClientStream = streamId & 1;

        if (endPointMode == -1) {
            /**
             * The remote endpoint created this stream.
             * We are a server if stream id is odd, and a client if it's even.
             */
            endPointMode = isClientStream == 1 ? 0 : 1;
        }

        return endPointMode != isClientStream;
    }

    private void resetStreamAndClose(ChannelHandlerContext ctx, int streamId, Throwable t) {
        final Http2Exception http2Ex = Http2CodecUtil.getEmbeddedHttp2Exception(t);
        final Http2Error error = http2Ex != null
                ? http2Ex.error()
                : Http2Error.INTERNAL_ERROR;

        ctx.write(new DefaultHttp2ResetFrame(error).setStreamId(streamId));

        try {
            onStreamClosed(this.ctx, streamId, null);
        } catch (Throwable t1) {
            // Intentionally left empty.
        }
    }

    static final class StreamInfo<T> {
        boolean endOfStreamReceived;
        boolean endOfStreamSent;

        final T managedState;

        StreamInfo(T managedState) {
            this.managedState = managedState;
        }
    }

    final class WriteHandler extends ChannelDuplexHandler {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (!(evt instanceof Http2OutboundStreamActiveEvent)) {
                ctx.fireUserEventTriggered(evt);
                return;
            }

            final Http2OutboundStreamActiveEvent streamActive = (Http2OutboundStreamActiveEvent) evt;
            final int streamId = streamActive.streamId();

            if (endPointMode == -1) {
                /**
                 * This endpoint created this stream.
                 * We are a client if stream id is odd, and a server if it's even.
                 */
                endPointMode = streamId & 1;
            }

            try {
                final T managedState = onStreamActive(Http2ManagedStreamStateHandler.this.ctx, streamId,
                                                      streamActive.headers());
                final StreamInfo<T> streamInfo = new StreamInfo<T>(managedState);

                activeStreams.put(streamId, streamInfo);

                streamInfo.endOfStreamSent = streamActive.headers().isEndStream();
            } catch (Throwable t) {
                resetStreamAndClose(ctx, streamId, t);

                Http2ManagedStreamStateHandler.this.ctx.fireExceptionCaught(t);
            }
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (endOfStreamSet(msg) && ((Http2StreamFrame) msg).hasStreamId()) {
                /**
                 * Header frames creating a new stream will not have a stream identifier set. This case is handled
                 * in {@link #userEventTriggered}.
                 */
                final Http2StreamFrame streamFrame = (Http2StreamFrame) msg;
                final StreamInfo<T> streamInfo = activeStreams.get(streamFrame.getStreamId());

                streamInfo.endOfStreamSent = true;

                tryCloseStream(streamInfo, streamFrame.getStreamId());
            } else if (msg instanceof Http2ResetFrame) {
                final Http2ResetFrame rstFrame = (Http2ResetFrame) msg;

                closeStream(activeStreams.get(rstFrame.getStreamId()), rstFrame.getStreamId());
            }

            ctx.write(msg, promise);
        }
    }
}
