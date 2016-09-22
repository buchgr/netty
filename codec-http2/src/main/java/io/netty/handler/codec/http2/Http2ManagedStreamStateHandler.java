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
import io.netty.channel.ChannelPromise;
import io.netty.util.collection.IntObjectHashMap;

/**
 * Automatically manages the lifecycle of streams. Allows to attach an object of type T to a stream. Cleans it up
 * automatically.
 */
public abstract class Http2ManagedStreamStateHandler<T> extends ChannelDuplexHandler {

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

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        activeStreams = new IntObjectHashMap<StreamInfo<T>>();
        endPointMode = -1;
        largestRemoteStreamIdentifier = 0;
        super.handlerAdded(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        activeStreams = null;
        endPointMode = -1;
        largestRemoteStreamIdentifier = 0;
        super.handlerRemoved(ctx);
    }

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) {
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
            streamInfo = new StreamInfo<T>(newManagedState(streamId));
            activeStreams.put(streamId, streamInfo);
            largestRemoteStreamIdentifier = streamId;
        } else {
            streamInfo = activeStreams.get(streamId);
        }
        if (msg instanceof Http2ResetFrame) {
            activeStreams.remove(streamId);
        } else {
            streamInfo.endOfStreamReceived |= endOfStreamSet(streamFrame);
            removeStreamIfClosed(streamInfo, streamId);
        }

        channelRead(ctx, streamFrame, streamInfo.managedState);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (endOfStreamSet(msg) && ((Http2StreamFrame) msg).hasStreamId()) {
            Http2StreamFrame streamFrame = (Http2StreamFrame) msg;
            // Header frames initiating a new stream will not have a stream identifier set.
            if (streamFrame.hasStreamId()) {
                final StreamInfo<T> streamInfo = activeStreams.get(streamFrame.getStreamId());
                streamInfo.endOfStreamSent = true;
                removeStreamIfClosed(streamInfo, streamFrame.getStreamId());
            }
        } else if (msg instanceof Http2ResetFrame) {
            activeStreams.remove(((Http2StreamFrame) msg).getStreamId());
        }
        ctx.write(msg, promise);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (!(evt instanceof Http2StreamActiveEvent)) {
            super.userEventTriggered(ctx, evt);
            return;
        }
        final Http2StreamActiveEvent streamActive = (Http2StreamActiveEvent) evt;
        final int streamId = streamActive.streamId();
        if (endPointMode == -1) {
            // This endpoint created this stream. We are a client if stream id is odd, and a server if it's even.
            endPointMode = streamId & 1;
        }
        final StreamInfo<T> streamInfo = new StreamInfo<T>(newManagedState(streamId));
        activeStreams.put(streamId, streamInfo);
        streamInfo.endOfStreamSent = streamActive.headers().isEndStream();
    }

    protected abstract void channelRead(ChannelHandlerContext ctx, Http2StreamFrame frame, T managedState);

    protected abstract void channelRead(ChannelHandlerContext ctx, Http2Frame frame);

    protected abstract T newManagedState(int streamId);

    private void removeStreamIfClosed(StreamInfo<T> streamInfo, int streamId) {
        if (streamInfo != null && streamInfo.endOfStreamReceived && streamInfo.endOfStreamSent) {
            activeStreams.remove(streamId);
        }
    }

    private static boolean endOfStreamSet(Object streamFrame) {
        return streamFrame instanceof Http2HeadersFrame && ((Http2HeadersFrame) streamFrame).isEndStream()
               || streamFrame instanceof Http2DataFrame && ((Http2DataFrame) streamFrame).isEndStream();
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
