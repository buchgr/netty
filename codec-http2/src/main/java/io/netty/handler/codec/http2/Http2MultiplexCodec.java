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
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPromiseNotifier;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.handler.codec.http2.Http2MultiplexCodec.Http2StreamChannel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static io.netty.handler.codec.http2.AbstractHttp2StreamChannel.CLOSE_MESSAGE;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.lang.String.format;

/**
 * An HTTP/2 handler that creates child channels for each stream.
 *
 * <p>When a new stream is created, a new {@link Channel} is created for it. Applications send and
 * receive {@link Http2StreamFrame}s on the created channel. {@link ByteBuf}s cannot be processed by the channel;
 * all writes that reach the head of the pipeline must be an instance of {@link Http2StreamFrame}. Writes that reach
 * the head of the pipeline are processed directly by this handler and cannot be intercepted.
 *
 * <p>The child channel will be notified of user events that impact the stream, such as {@link
 * Http2GoAwayFrame} and {@link Http2ResetFrame}, as soon as they occur. Although {@code
 * Http2GoAwayFrame} and {@code Http2ResetFrame} signify that the remote is ignoring further
 * communication, closing of the channel is delayed until any inbound queue is drained with {@link
 * Channel#read()}, which follows the default behavior of channels in Netty. Applications are
 * free to close the channel in response to such events if they don't have use for any queued
 * messages.
 *
 * <p>Outbound streams are supported via the {@link Http2StreamChannelBootstrap}.
 *
 * <p>{@link ChannelConfig#setMaxMessagesPerRead(int)} and {@link ChannelConfig#setAutoRead(boolean)} are supported.
 */
@UnstableApi
public final class Http2MultiplexCodec extends Http2ManagedStreamStateHandler<Http2StreamChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Http2MultiplexCodec.class);

    private final Http2StreamChannelBootstrap bootstrap;

    private final List<Http2StreamChannel> channelsToFireChildReadComplete = new ArrayList<Http2StreamChannel>();
    private final boolean server;
    private ChannelHandlerContext ctx;
    private volatile Runnable flushTask;

    private int initialOutboundStreamWindow = Http2CodecUtil.DEFAULT_WINDOW_SIZE;

    /**
     * Construct a new handler whose child channels run in a different event loop.
     *
     * @param server {@code true} this is a server
     * @param bootstrap bootstrap used to instantiate child channels for remotely-created streams.
     */
    public Http2MultiplexCodec(boolean server, Http2StreamChannelBootstrap bootstrap) {
        this.server = server;
        this.bootstrap = new Http2StreamChannelBootstrap(checkNotNull(bootstrap, "bootstrap must not be null"));
        if (bootstrap.parentChannel() != null) {
            throw new IllegalStateException("The parent channel must not be set on the bootstrap.");
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        bootstrap.parentChannel(ctx.channel());
    }

    // Override this to signal it will never throw an exception.
    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http2Frame frame) {
        if (frame instanceof Http2GoAwayFrame) {
            final Http2GoAwayFrame goAwayFrame = (Http2GoAwayFrame) frame;
            forEachActiveStream(new StreamVisitor<Http2StreamChannel>() {
                @Override
                public boolean visit(Http2StreamChannel childChannel) {
                    final int streamId = childChannel.getStreamId();
                    if (streamId > goAwayFrame.lastStreamId() && isOutbound(streamId)) {
                        childChannel.pipeline().fireUserEventTriggered(goAwayFrame.retainedDuplicate());
                    }
                    return true;
                }
            });
            goAwayFrame.release();
        } else if (frame instanceof Http2SettingsFrame) {
            Http2Settings settings = ((Http2SettingsFrame) frame).settings();
            if (settings.initialWindowSize() != null) {
                initialOutboundStreamWindow = settings.initialWindowSize();
            }
        } else {
            // It's safe to release, as UnsupportedMessageTypeException just calls msg.getClass()
            ReferenceCountUtil.release(frame);
            throw new UnsupportedMessageTypeException(frame);
        }
    }

    @Override
    protected Http2StreamChannel onStreamActive(int streamId, Http2HeadersFrame headersFrame) {
        final Http2StreamChannel childChannel;
        if (isOutbound(streamId)) {
            if (!(headersFrame instanceof ChannelCarryingHeadersFrame)) {
                throw new IllegalArgumentException("needs to be wrapped");
            }
            childChannel = ((ChannelCarryingHeadersFrame) headersFrame).channel();
            childChannel.setStreamId(streamId);
        } else {
            ChannelFuture future = bootstrap.connect(streamId);
            childChannel = (Http2StreamChannel) future.channel();
        }

        assert !childChannel.isWritable();
        childChannel.incrementOutboundFlowControlWindow(initialOutboundStreamWindow);
        childChannel.pipeline().fireChannelWritabilityChanged();

        return childChannel;
    }

    @Override
    protected void onStreamClosed(final Http2StreamChannel childChannel) {
        final EventLoop eventLoop = childChannel.eventLoop();
        if (eventLoop.inEventLoop()) {
            streamClosed0(childChannel);
        } else {
            eventLoop.execute(new Runnable() {
                @Override
                public void run() {
                    streamClosed0(childChannel);
                }
            });
        }
    }

    @Override
    protected void onStreamError(Throwable cause, StreamException streamException, Http2StreamChannel childChannel)
            throws Exception {
        try {
            childChannel.pipeline().fireExceptionCaught(streamException);
        } finally {
            Http2Error error = streamException.error();
            int streamId = streamException.streamId();
            write(ctx, new DefaultHttp2ResetFrame(error).setStreamId(streamId), ctx.newPromise());
        }
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http2StreamFrame frame, Http2StreamChannel childChannel)
            throws Exception {
        fireChildReadAndRegister(childChannel, frame);
    }

    private void fireChildReadAndRegister(Http2StreamChannel childChannel, Http2StreamFrame frame) {
        // Can't use childChannel.fireChannelRead() as it would fire independent of whether
        // channel.read() had been called.
        childChannel.fireChildRead(frame);
        if (!childChannel.inStreamsToFireChildReadComplete) {
            channelsToFireChildReadComplete.add(childChannel);
            childChannel.inStreamsToFireChildReadComplete = true;
        }
    }

    private void streamClosed0(Http2StreamChannel childChannel) {
        assert childChannel.eventLoop().inEventLoop();

        childChannel.onStreamClosedFired = true;
        childChannel.fireChildRead(CLOSE_MESSAGE);
    }

    void flushFromStreamChannel() {
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            flush(ctx);
        } else {
            Runnable task = flushTask;
            if (task == null) {
                task = flushTask = new Runnable() {
                    @Override
                    public void run() {
                        flush(ctx);
                    }
                };
            }
            executor.execute(task);
        }
    }

    void writeFromStreamChannel(final Object msg, final ChannelPromise promise, final boolean flush) {
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            writeFromStreamChannel0(msg, flush, promise);
        } else {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        writeFromStreamChannel0(msg, flush, promise);
                    }
                });
            } catch (Throwable cause) {
                promise.setFailure(cause);
            }
        }
    }

    private void writeFromStreamChannel0(Object msg, boolean flush, ChannelPromise promise) {
        try {
            write(ctx, msg, promise);
        } catch (Throwable cause) {
            promise.tryFailure(cause);
        }
        if (flush) {
            flush(ctx);
        }
    }

    /**
     * Notifies any child streams of the read completion.
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        for (int i = 0; i < channelsToFireChildReadComplete.size(); i++) {
            Http2StreamChannel childChannel = channelsToFireChildReadComplete.get(i);
            // Clear early in case fireChildReadComplete() causes it to need to be re-processed
            childChannel.inStreamsToFireChildReadComplete = false;
            childChannel.fireChildReadComplete();
        }
        channelsToFireChildReadComplete.clear();
    }

    ChannelFuture createStreamChannel(Channel parentChannel, EventLoopGroup group, ChannelHandler handler,
                                              Map<ChannelOption<?>, Object> options,
                                              Map<AttributeKey<?>, Object> attrs,
                                              int streamId) {
        final Http2StreamChannel channel = new Http2StreamChannel(parentChannel);
        if (streamId != -1) {
            assert !isOutbound(streamId);
            assert ctx.channel().eventLoop().inEventLoop();
            channel.setStreamId(streamId);
        }
        channel.pipeline().addLast(handler);

        initOpts(channel, options);
        initAttrs(channel, attrs);

        ChannelFuture future = group.register(channel);
        // Handle any errors that occurred on the local thread while registering. Even though
        // failures can happen after this point, they will be handled by the channel by closing the
        // channel.
        if (future.cause() != null) {
            if (channel.isRegistered()) {
                channel.close();
            } else {
                channel.unsafe().closeForcibly();
            }
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    private static void initOpts(Channel channel, Map<ChannelOption<?>, Object> opts) {
        if (opts != null) {
            synchronized (opts) {
                for (Entry<ChannelOption<?>, Object> e: opts.entrySet()) {
                    try {
                        if (!channel.config().setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
                            logger.warn("Unknown channel option: " + e);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to set a channel option: " + channel, t);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void initAttrs(Channel channel, Map<AttributeKey<?>, Object> attrs) {
        if (attrs != null) {
            synchronized (attrs) {
                for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
                    channel.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
                }
            }
        }
    }

    final class Http2StreamChannel extends AbstractHttp2StreamChannel implements ChannelFutureListener {
        boolean onStreamClosedFired;

        /**
         * {@code true} if stream is in {@link Http2MultiplexCodec#channelsToFireChildReadComplete}.
         */
        boolean inStreamsToFireChildReadComplete;

        Http2StreamChannel(Channel parentChannel) {
            super(parentChannel);
        }

        @Override
        protected void doClose() throws Exception {
            if (!onStreamClosedFired && hasStreamId()) {
                Http2StreamFrame resetFrame = new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(getStreamId());
                writeFromStreamChannel(resetFrame, ctx.newPromise(), true);
            }
            super.doClose();
        }

        @Override
        protected void doWrite(Object msg, ChannelPromise childPromise) {
            Http2StreamFrame frame = validateStreamFrame(msg);
            if (!hasStreamId()) {
                if (!(frame instanceof Http2HeadersFrame)) {
                    throw new IllegalArgumentException("The first frame must be a headers frame.");
                }
                frame = new ChannelCarryingHeadersFrame((Http2HeadersFrame) frame, this);
                // Handle errors on stream creation
                childPromise.addListener(this);
            } else {
                frame.setStreamId(getStreamId());
            }
            /**
             * Wrap the ChannelPromise of the child channel in a ChannelPromise of the parent channel
             * in order to be able to use it on the parent channel. We don't need to worry about the
             * channel being cancelled, as the outbound buffer of the child channel marks it uncancelable.
             */
            assert !childPromise.isCancellable();
            ChannelFutureListener childPromiseNotifier = new ChannelPromiseNotifier(childPromise);
            ChannelPromise parentPromise = ctx.newPromise().addListener(childPromiseNotifier);
            writeFromStreamChannel(frame, parentPromise, false);
        }

        @Override
        protected void doWriteComplete() {
            flushFromStreamChannel();
        }

        @Override
        protected void bytesConsumed(final int bytes) {
            ctx.write(new DefaultHttp2WindowUpdateFrame(bytes).setStreamId(getStreamId()));
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (future.cause() != null) {
                pipeline().fireExceptionCaught(future.cause());
                close();
            }
        }

        private Http2StreamFrame validateStreamFrame(Object msg) {
            if (!(msg instanceof Http2StreamFrame)) {
                ReferenceCountUtil.release(msg);
                throw new IllegalArgumentException("Message must be an Http2StreamFrame: " + msg);
            }
            Http2StreamFrame frame = (Http2StreamFrame) msg;
            if (frame.hasStreamId()) {
                ReferenceCountUtil.release(frame);
                throw new IllegalArgumentException("Stream identifier must not be set on the frame.");
            }
            return frame;
        }
    }

    /**
     * Wraps the first {@link Http2HeadersFrame} of local/outbound stream. This allows us to get to the child channel
     * when receiving the {@link Http2OutgoingStreamActive} from the frame codec. See {@link #onStreamActive}.
     */
    private static final class ChannelCarryingHeadersFrame implements Http2HeadersFrame {

        private final Http2HeadersFrame frame;
        private final Http2StreamChannel childChannel;

        ChannelCarryingHeadersFrame(Http2HeadersFrame frame, Http2StreamChannel childChannel) {
            this.frame = frame;
            this.childChannel = childChannel;
        }

        @Override
        public Http2Headers headers() {
            return frame.headers();
        }

        @Override
        public boolean isEndStream() {
            return frame.isEndStream();
        }

        @Override
        public int padding() {
            return frame.padding();
        }

        @Override
        public Http2StreamFrame setStreamId(int streamId) {
            return frame.setStreamId(streamId);
        }

        @Override
        public int getStreamId() {
            return frame.getStreamId();
        }

        @Override
        public boolean hasStreamId() {
            return frame.hasStreamId();
        }

        @Override
        public String name() {
            return frame.name();
        }

        Http2StreamChannel channel() {
            return childChannel;
        }
    }

    private boolean isOutbound(int streamId) {
        boolean even = (streamId & 1) == 0;
        return streamId > 0 && server == even;
    }
}
