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
import io.netty.channel.ChannelFuture;
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

    protected abstract void channelRead(ChannelHandlerContext ctx, Http2StreamFrame frame, T managedState)
            throws Exception;

    protected abstract void channelRead(ChannelHandlerContext ctx, Http2Frame frame) throws Exception;

    protected abstract T onStreamActive(int streamId, Http2HeadersFrame firstHeaders) throws Exception;

    protected abstract void onStreamClosed(T managedState) throws Exception;

    protected abstract void onStreamError(Throwable cause, StreamException streamException, T managedState)
            throws Exception;

    protected abstract void onConnectionError(Throwable cause, Http2Exception http2Ex);

    protected ChannelFuture writeFrame(Http2Frame frame) {
        return writeFrame(frame, ctx.newPromise());
    }

    protected ChannelFuture writeFrame(Http2Frame frame, ChannelPromise promise) {
        write0(ctx, frame, promise);
        return promise;
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

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        activeStreams = new IntObjectHashMap<StreamInfo<T>>();
        endPointMode = -1;
        largestRemoteStreamIdentifier = 0;
        this.ctx = ctx;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        activeStreams = null;
        endPointMode = -1;
        largestRemoteStreamIdentifier = 0;
        this.ctx = null;
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
        final StreamInfo<T> streamInfo;
        if (isRemoteStream(streamId) && streamId > largestRemoteStreamIdentifier) {
            if (!(msg instanceof Http2HeadersFrame)) {
                throw new IllegalStateException("The first frame must be HEADERS.");
            }
            streamInfo = new StreamInfo<T>(onStreamActive(streamId, (Http2HeadersFrame) msg));
            activeStreams.put(streamId, streamInfo);
            largestRemoteStreamIdentifier = streamId;
            return;
        }

        streamInfo = activeStreams.get(streamId);
        if (msg instanceof Http2ResetFrame) {
            closeStream(streamInfo, streamId);
        } else {
            streamInfo.endOfStreamReceived |= endOfStreamSet(streamFrame);
            tryCloseStream(streamInfo, streamId);
        }

        channelRead(ctx, streamFrame, streamInfo.managedState);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        write0(ctx, msg, promise);
    }

    private void write0(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (endOfStreamSet(msg) && ((Http2StreamFrame) msg).hasStreamId()) {
            Http2StreamFrame streamFrame = (Http2StreamFrame) msg;
            // Header frames initiating a new stream will not have a stream identifier set.
            if (streamFrame.hasStreamId()) {
                final StreamInfo<T> streamInfo = activeStreams.get(streamFrame.getStreamId());
                streamInfo.endOfStreamSent = true;
                tryCloseStream(streamInfo, streamFrame.getStreamId());
            }
        } else if (msg instanceof Http2ResetFrame) {
            Http2StreamFrame streamFrame = (Http2StreamFrame) msg;
            closeStream(activeStreams.get(streamFrame.getStreamId()), streamFrame.getStreamId());
            activeStreams.remove(((Http2StreamFrame) msg).getStreamId());
        }

        ctx.write(msg, promise);
    }

    @Override
    public final void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (!(evt instanceof Http2OutboundStreamActiveEvent)) {
            super.userEventTriggered(ctx, evt);
            return;
        }
        final Http2OutboundStreamActiveEvent streamActive = (Http2OutboundStreamActiveEvent) evt;
        final int streamId = streamActive.streamId();
        if (endPointMode == -1) {
            // This endpoint created this stream. We are a client if stream id is odd, and a server if it's even.
            endPointMode = streamId & 1;
        }
        final StreamInfo<T> streamInfo = new StreamInfo<T>(onStreamActive(streamId, streamActive.headers()));
        activeStreams.put(streamId, streamInfo);
        streamInfo.endOfStreamSent = streamActive.headers().isEndStream();
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Http2Exception http2Ex = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (isStreamError(http2Ex)) {
            StreamException streamException = (StreamException) http2Ex;
            StreamInfo<T> streamInfo = activeStreams.get(streamException.streamId());
            if (streamInfo != null) {
                onStreamError(cause, streamException, streamInfo.managedState);
            }
        } else if (http2Ex instanceof CompositeStreamException) {
            // Multiple exceptions for (different) streams wrapped in one exception.
            CompositeStreamException compositeException = (CompositeStreamException) http2Ex;
            for (StreamException streamException : compositeException) {
                StreamInfo<T> streamInfo = activeStreams.get(streamException.streamId());
                if (streamInfo != null) {
                    onStreamError(cause, streamException, streamInfo.managedState);
                }
            }
        } else {
            onConnectionError(cause, http2Ex);
        }
    }

    private void tryCloseStream(StreamInfo<T> streamInfo, int streamId) {
        if (streamInfo != null && streamInfo.endOfStreamReceived && streamInfo.endOfStreamSent) {
            closeStream(streamInfo, streamId);
        }
    }

    private static boolean endOfStreamSet(Object streamFrame) {
        return streamFrame instanceof Http2HeadersFrame && ((Http2HeadersFrame) streamFrame).isEndStream()
               || streamFrame instanceof Http2DataFrame && ((Http2DataFrame) streamFrame).isEndStream();
    }

    private void closeStream(StreamInfo<T> streamInfo, int streamId) {
        try {
            onStreamClosed(streamInfo.managedState);
        } catch (Throwable t) {
            // TODO log
        } finally {
            activeStreams.remove(streamId);
        }
    }

    private boolean isRemoteStream(int streamId) {
        final int isClientStream = streamId & 1;
        if (endPointMode == -1) {
            // The remote endpoint created this stream. We are a server if stream id is odd, and a client if it's even.
            endPointMode = isClientStream == 1 ? 0 : 1;
        }
        return endPointMode != isClientStream;
    }

    static final class StreamInfo<T> {
        boolean endOfStreamReceived;
        boolean endOfStreamSent;

        final T managedState;

        StreamInfo(T managedState) {
            this.managedState = managedState;
        }
    }
}
