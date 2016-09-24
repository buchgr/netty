/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.internal.PlatformDependent;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static io.netty.util.ReferenceCountUtil.release;
import static io.netty.util.ReferenceCountUtil.releaseLater;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link Http2MultiplexCodec} and {@link Http2StreamChannelBootstrap}.
 */
public class Http2MultiplexCodecTest {

    private EmbeddedChannel parentChannel;

    private TestChannelInitializer childChannelInitializer;

    private static final Http2Headers request = new DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName()).scheme(HttpScheme.HTTPS.name())
            .authority(new AsciiString("example.org")).path(new AsciiString("/foo"));

    private static final int incomingStreamId = 3;
    private static final int outgoingStreamId = 2;
    private static final int initialWindowSize = 1024;

    @Before
    public void setUp() {
        childChannelInitializer = new TestChannelInitializer();
        Http2StreamChannelBootstrap bootstrap = new Http2StreamChannelBootstrap().handler(childChannelInitializer);
        parentChannel = new EmbeddedChannel();
        parentChannel.connect(new InetSocketAddress(0));
        parentChannel.pipeline().addLast(new Http2MultiplexCodec(true, bootstrap));
    }

    @After
    public void tearDown() throws Exception {
        if (childChannelInitializer.handler != null) {
            ((LastInboundHandler) childChannelInitializer.handler).finishAndReleaseAll();
        }
        parentChannel.finishAndReleaseAll();
    }

    // TODO(buchgr): Thread model of child channel
    // TODO(buchgr): Flush from child channel
    // TODO(buchgr): ChildChannel.childReadComplete()
    // TODO(buchgr): GOAWAY Logic
    // TODO(buchgr): Test ChannelConfig.setMaxMessagesPerRead

    @Test
    public void headerAndDataFramesShouldBeDelivered() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;

        Http2StreamActiveEvent streamActive = new Http2StreamActiveEvent(incomingStreamId, initialWindowSize);
        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(request).setStreamId(incomingStreamId);
        Http2DataFrame dataFrame1 = releaseLater(new DefaultHttp2DataFrame(bb("hello")).setStreamId(incomingStreamId));
        Http2DataFrame dataFrame2 = releaseLater(new DefaultHttp2DataFrame(bb("world")).setStreamId(incomingStreamId));

        assertFalse(inboundHandler.channelActive);
        parentChannel.pipeline().fireUserEventTriggered(streamActive);
        assertTrue(inboundHandler.channelActive);
        // Make sure the stream active event is not delivered as a user event on the child channel.
        assertNull(inboundHandler.readUserEvent());
        parentChannel.pipeline().fireChannelRead(headersFrame);
        parentChannel.pipeline().fireChannelRead(dataFrame1);
        parentChannel.pipeline().fireChannelRead(dataFrame2);

        assertEquals(headersFrame, inboundHandler.readInbound());
        assertEquals(dataFrame1, inboundHandler.readInbound());
        assertEquals(dataFrame2, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());
    }

    @Test
    public void framesShouldBeMultiplexed() {
        LastInboundHandler inboundHandler3 = streamActiveAndWriteHeaders(3);
        LastInboundHandler inboundHandler11 = streamActiveAndWriteHeaders(11);
        LastInboundHandler inboundHandler5 = streamActiveAndWriteHeaders(5);

        verifyFramesMultiplexedToCorrectChannel(3, inboundHandler3, 1);
        verifyFramesMultiplexedToCorrectChannel(5, inboundHandler5, 1);
        verifyFramesMultiplexedToCorrectChannel(11, inboundHandler11, 1);

        parentChannel.pipeline().fireChannelRead(new DefaultHttp2DataFrame(bb("hello"), false).setStreamId(5));
        parentChannel.pipeline().fireChannelRead(new DefaultHttp2DataFrame(bb("foo"), true).setStreamId(3));
        parentChannel.pipeline().fireChannelRead(new DefaultHttp2DataFrame(bb("world"), true).setStreamId(5));
        parentChannel.pipeline().fireChannelRead(new DefaultHttp2DataFrame(bb("bar"), true).setStreamId(11));
        verifyFramesMultiplexedToCorrectChannel(5, inboundHandler5, 2);
        verifyFramesMultiplexedToCorrectChannel(3, inboundHandler3, 1);
        verifyFramesMultiplexedToCorrectChannel(11, inboundHandler11, 1);
    }

    @Test
    public void inboundDataFrameShouldEmitWindowUpdateFrame() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);
        ByteBuf tenBytes = bb("0123456789");
        parentChannel.pipeline().fireChannelRead(
                new DefaultHttp2DataFrame(tenBytes, true).setStreamId(incomingStreamId));
        parentChannel.pipeline().flush();

        Http2WindowUpdateFrame windowUpdate = parentChannel.readOutbound();
        assertNotNull(windowUpdate);
        assertEquals(incomingStreamId, windowUpdate.getStreamId());
        assertEquals(10, windowUpdate.windowSizeIncrement());

        // headers and data frame
        verifyFramesMultiplexedToCorrectChannel(incomingStreamId, inboundHandler, 2);
    }

    @Test
    public void channelReadShouldRespectAutoRead() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);
        Channel childChannel = inboundHandler.channel();
        assertTrue(childChannel.config().isAutoRead());
        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        childChannel.config().setAutoRead(false);
        parentChannel.pipeline().fireChannelRead(
                new DefaultHttp2DataFrame(bb("hello world"), false).setStreamId(incomingStreamId));
        parentChannel.pipeline().fireChannelReadComplete();
        Http2DataFrame dataFrame0 = inboundHandler.readInbound();
        assertNotNull(dataFrame0);
        release(dataFrame0);

        parentChannel.pipeline().fireChannelRead(new DefaultHttp2DataFrame(bb("foo"), false).setStreamId(
                incomingStreamId));
        parentChannel.pipeline().fireChannelRead(new DefaultHttp2DataFrame(bb("bar"), true).setStreamId(
                incomingStreamId));
        parentChannel.pipeline().fireChannelReadComplete();

        dataFrame0 = inboundHandler.readInbound();
        assertNull(dataFrame0);

        childChannel.config().setAutoRead(true);
        verifyFramesMultiplexedToCorrectChannel(incomingStreamId, inboundHandler, 2);
    }

    /**
     * A child channel for a HTTP/2 stream in IDLE state (that is no headers sent or received),
     * should not emit a RST_STREAM frame on close, as this is a connection error of type protocol error.
     */
    @Test
    public void idleOutboundStreamShouldNotWriteResetFrameOnClose() {
        childChannelInitializer.handler = new LastInboundHandler();

        Http2StreamChannelBootstrap b = new Http2StreamChannelBootstrap();
        b.parentChannel(parentChannel).handler(childChannelInitializer);
        Channel childChannel = b.connect().channel();
        assertTrue(childChannel.isActive());

        childChannel.close();
        parentChannel.runPendingTasks();

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertNull(parentChannel.readOutbound());
    }

    @Test
    public void outboundStreamShouldWriteResetFrameOnClose_headersSent() {
        childChannelInitializer.handler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                ctx.fireChannelActive();
            }
        };

        Http2StreamChannelBootstrap b = new Http2StreamChannelBootstrap();
        b.parentChannel(parentChannel).handler(childChannelInitializer);
        Channel childChannel = b.connect().channel();
        assertTrue(childChannel.isActive());

        parentChannel.flush();
        Http2HeadersFrame headersFrame = parentChannel.readOutbound();
        assertNotNull(headersFrame);
        assertFalse(headersFrame.hasStreamId());

        parentChannel.pipeline().fireUserEventTriggered(
                new Http2StreamActiveEvent(outgoingStreamId, initialWindowSize, headersFrame));

        childChannel.close();
        parentChannel.runPendingTasks();

        Http2ResetFrame reset = parentChannel.readOutbound();
        assertEquals(2, reset.getStreamId());
        assertEquals(Http2Error.CANCEL.code(), reset.errorCode());
    }

    @Test
    public void inboundStreamClosedShouldFireChannelInactive() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);
        assertTrue(inboundHandler.channelActive);

        parentChannel.pipeline().fireUserEventTriggered(new Http2StreamClosedEvent(incomingStreamId));
        parentChannel.runPendingTasks();
        parentChannel.flush();

        assertFalse(inboundHandler.channelActive);
        // A RST_STREAM frame should NOT be emitted, as we received the close.
        assertNull(parentChannel.readOutbound());
    }

    @Test(expected = StreamException.class)
    public void streamExceptionTriggersChildChannelExceptionCaught() throws Exception {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);

        StreamException e = new StreamException(incomingStreamId, Http2Error.PROTOCOL_ERROR, "baaam!");
        parentChannel.pipeline().fireExceptionCaught(e);

        inboundHandler.checkException();
    }

    @Test
    public void streamExceptionClosesChildChannel() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);

        assertTrue(inboundHandler.channelActive);
        StreamException e = new StreamException(incomingStreamId, Http2Error.PROTOCOL_ERROR, "baaam!");
        parentChannel.pipeline().fireExceptionCaught(e);
        parentChannel.runPendingTasks();

        assertFalse(inboundHandler.channelActive);
    }

    @Test
    public void creatingWritingReadingAndClosingOutboundStreamShouldWork() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;

        Http2StreamChannelBootstrap b = new Http2StreamChannelBootstrap();
        b.parentChannel(parentChannel).handler(childChannelInitializer);
        AbstractHttp2StreamChannel childChannel = (AbstractHttp2StreamChannel) b.connect().channel();
        assertThat(childChannel, Matchers.instanceOf(Http2MultiplexCodec.Http2StreamChannel.class));
        assertTrue(childChannel.isActive());
        assertTrue(inboundHandler.channelActive);

        // Write to the child channel
        Http2Headers headers = new DefaultHttp2Headers().scheme("https").method("GET").path("/foo.txt");
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));
        parentChannel.flush();

        Http2HeadersFrame headersFrame = parentChannel.readOutbound();
        assertNotNull(headersFrame);
        assertSame(headers, headersFrame.headers());
        assertFalse(headersFrame.hasStreamId());

        parentChannel.pipeline().fireUserEventTriggered(
                new Http2StreamActiveEvent(outgoingStreamId, initialWindowSize, headersFrame));

        // Read from the child channel
        headers = new DefaultHttp2Headers().scheme("https").status("200");
        parentChannel.pipeline().fireChannelRead(
                new DefaultHttp2HeadersFrame(headers).setStreamId(childChannel.getStreamId()));
        parentChannel.pipeline().fireChannelReadComplete();

        headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        // Close the child channel.
        childChannel.close();

        parentChannel.flush();
        parentChannel.runPendingTasks();
        // An active outbound stream should emit a RST_STREAM frame.
        Http2ResetFrame rstFrame = parentChannel.readOutbound();
        assertNotNull(rstFrame);
        assertEquals(childChannel.getStreamId(), rstFrame.getStreamId());
        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertFalse(inboundHandler.channelActive);
    }

    /**
     * Test failing the promise of the first headers frame of an outbound stream. In pratice this error case would most
     * likely happen due to the max concurrent streams limit being hit or the channel running out of stream identifiers.
     */
    @Test(expected = Http2NoMoreStreamIdsException.class)
    public void failedOutboundStreamCreationThrowsAndClosesChannel() throws Exception {
        final AtomicReference<ChannelPromise> promiseRef = new AtomicReference<ChannelPromise>();
        // This is a hack, as promises for writes in a child channel are currently not implemented correctly, as they
        // are always completed successfully. We need to install our own handler on the parent channel in order to get
        // to the channel promise were write errors are signaled.
        parentChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.tryFailure(new Http2NoMoreStreamIdsException());
                promiseRef.set(promise);
            }
        });

        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;

        Http2StreamChannelBootstrap b = new Http2StreamChannelBootstrap();
        Channel childChannel = b.parentChannel(parentChannel).handler(childChannelInitializer).connect().channel();
        assertTrue(childChannel.isActive());

        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        parentChannel.flush();
        ChannelPromise promise = promiseRef.get();
        assertNotNull(promise);

        parentChannel.runPendingTasks();

        assertFalse(childChannel.isActive());
        assertFalse(childChannel.isOpen());

        inboundHandler.checkException();
    }

    @Test
    public void settingChannelOptsAndAttrsOnBootstrap() {
        AttributeKey<String> key = AttributeKey.newInstance("foo");
        WriteBufferWaterMark mark = new WriteBufferWaterMark(1024, 4096);
        Http2StreamChannelBootstrap b = new Http2StreamChannelBootstrap();
        b.parentChannel(parentChannel).handler(childChannelInitializer)
         .option(ChannelOption.AUTO_READ, false).option(ChannelOption.WRITE_SPIN_COUNT, 1000)
         .attr(key, "bar");

        Channel channel = b.connect().channel();

        assertFalse(channel.config().isAutoRead());
        assertEquals(1000, channel.config().getWriteSpinCount());
        assertEquals("bar", channel.attr(key).get());
    }

    @Test
    public void outboundFlowControlWindowShouldBeSetAndUpdated() {
        Http2StreamChannelBootstrap b = new Http2StreamChannelBootstrap();
        b.parentChannel(parentChannel).handler(childChannelInitializer);
        AbstractHttp2StreamChannel childChannel = (AbstractHttp2StreamChannel) b.connect().channel();
        assertTrue(childChannel.isActive());

        assertEquals(0, childChannel.getOutboundFlowControlWindow());
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        parentChannel.flush();
        Http2HeadersFrame headers = parentChannel.readOutbound();
        assertNotNull(headers);

        parentChannel.pipeline().fireUserEventTriggered(
                new Http2StreamActiveEvent(outgoingStreamId, initialWindowSize, headers));
        // Test for initial window size
        assertEquals(initialWindowSize, childChannel.getOutboundFlowControlWindow());

        // Test for increment via WINDOW_UPDATE
        parentChannel.pipeline().fireChannelRead(new DefaultHttp2WindowUpdateFrame(1).setStreamId(outgoingStreamId));
        parentChannel.pipeline().fireChannelReadComplete();

        assertEquals(initialWindowSize + 1, childChannel.getOutboundFlowControlWindow());
    }

    @Test
    public void onlyDataFramesShouldBeFlowControlled() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);
        AbstractHttp2StreamChannel childChannel = (AbstractHttp2StreamChannel) inboundHandler.channel();
        assertTrue(childChannel.isWritable());
        assertEquals(initialWindowSize, childChannel.getOutboundFlowControlWindow());

        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        assertTrue(childChannel.isWritable());
        assertEquals(initialWindowSize, childChannel.getOutboundFlowControlWindow());

        childChannel.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.INTERNAL_ERROR));
        assertTrue(childChannel.isWritable());
        assertEquals(initialWindowSize, childChannel.getOutboundFlowControlWindow());

        ByteBuf data = Unpooled.buffer(100).writeZero(100);
        releaseLater(data);
        childChannel.writeAndFlush(new DefaultHttp2DataFrame(data));
        assertTrue(childChannel.isWritable());
        assertEquals(initialWindowSize - 100, childChannel.getOutboundFlowControlWindow());
    }

    @Test
    public void writabilityAndFlowControl() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);
        AbstractHttp2StreamChannel childChannel = (AbstractHttp2StreamChannel) inboundHandler.channel();
        verifyFlowControlWindowAndWritability(childChannel, initialWindowSize);
        assertEquals("true", inboundHandler.writabilityStates);

        // HEADERS frames are not flow controlled, so they should not affect the flow control window.
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        verifyFlowControlWindowAndWritability(childChannel, initialWindowSize);
        assertEquals("true", inboundHandler.writabilityStates);

        ByteBuf data = Unpooled.buffer(initialWindowSize - 1).writeZero(initialWindowSize - 1);
        releaseLater(data);
        childChannel.writeAndFlush(new DefaultHttp2DataFrame(data));
        verifyFlowControlWindowAndWritability(childChannel, 1);
        assertEquals("true", inboundHandler.writabilityStates);

        ByteBuf data1 = Unpooled.buffer(100).writeZero(100);
        releaseLater(data1);
        childChannel.writeAndFlush(new DefaultHttp2DataFrame(data1));
        verifyFlowControlWindowAndWritability(childChannel, -99);
        assertEquals("true,false", inboundHandler.writabilityStates);

        parentChannel.pipeline().fireChannelRead(new DefaultHttp2WindowUpdateFrame(99).setStreamId(incomingStreamId));
        parentChannel.pipeline().fireChannelReadComplete();
        // the flow control window should be updated, but the channel should still not be writable.
        verifyFlowControlWindowAndWritability(childChannel, 0);
        assertEquals("true,false", inboundHandler.writabilityStates);

        parentChannel.pipeline().fireChannelRead(new DefaultHttp2WindowUpdateFrame(1).setStreamId(incomingStreamId));
        parentChannel.pipeline().fireChannelReadComplete();
        verifyFlowControlWindowAndWritability(childChannel, 1);
        assertEquals("true,false,true", inboundHandler.writabilityStates);
    }

    @Test
    public void failedWriteShouldReturnFlowControlWindow() {
        ByteBuf data = Unpooled.buffer().writeZero(initialWindowSize);
        final Http2DataFrame frameToCancel = new DefaultHttp2DataFrame(releaseLater(data));
        parentChannel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg == frameToCancel) {
                    promise.tryFailure(new Throwable());
                } else {
                    super.write(ctx, msg, promise);
                }
            }
        });

        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);
        Channel childChannel = inboundHandler.channel();

        childChannel.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        data = Unpooled.buffer().writeZero(initialWindowSize / 2);
        childChannel.write(new DefaultHttp2DataFrame(releaseLater(data)));
        assertEquals("true", inboundHandler.writabilityStates);

        childChannel.write(frameToCancel);
        assertEquals("true,false", inboundHandler.writabilityStates);
        assertFalse(childChannel.isWritable());
        childChannel.flush();

        assertTrue(childChannel.isWritable());
        assertEquals("true,false,true", inboundHandler.writabilityStates);
    }

    @Test
    public void cancellingWritesBeforeFlush() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(incomingStreamId);
        Channel childChannel = inboundHandler.channel();

        Http2HeadersFrame headers1 = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers());
        Http2HeadersFrame headers2 = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers());
        ChannelPromise writePromise = childChannel.newPromise();
        childChannel.write(headers1, writePromise);
        childChannel.write(headers2);
        assertTrue(writePromise.cancel(false));
        childChannel.flush();

        Http2HeadersFrame headers = parentChannel.readOutbound();
        assertSame(headers, headers2);
    }

    private static void verifyFlowControlWindowAndWritability(AbstractHttp2StreamChannel channel,
                                                              int expectedWindowSize) {
        assertEquals(expectedWindowSize, channel.getOutboundFlowControlWindow());
        assertEquals(Math.max(0, expectedWindowSize), channel.config().getWriteBufferHighWaterMark());
        assertEquals(channel.config().getWriteBufferHighWaterMark(), channel.config().getWriteBufferLowWaterMark());
        assertEquals(expectedWindowSize > 0, channel.isWritable());
    }

    private LastInboundHandler streamActiveAndWriteHeaders(int streamId) {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;
        assertFalse(inboundHandler.channelActive);
        parentChannel.pipeline().fireUserEventTriggered(new Http2StreamActiveEvent(streamId, initialWindowSize));
        assertTrue(inboundHandler.channelActive);
        parentChannel.pipeline().fireChannelRead(new DefaultHttp2HeadersFrame(request).setStreamId(streamId));
        parentChannel.pipeline().fireChannelReadComplete();

        return inboundHandler;
    }

    private static void verifyFramesMultiplexedToCorrectChannel(int streamId, LastInboundHandler inboundHandler,
                                                                int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            Http2StreamFrame frame = inboundHandler.readInbound();
            assertNotNull(frame);
            assertEquals(streamId, frame.getStreamId());
            release(frame);
        }
        assertNull(inboundHandler.readInbound());
    }

    @Sharable
    static class TestChannelInitializer extends ChannelInitializer<Channel> {
        ChannelHandler handler;

        @Override
        public void initChannel(Channel channel) {
            if (handler != null) {
                channel.pipeline().addLast(handler);
                handler = null;
            }
        }
    }

    private static ByteBuf bb(String s) {
        return ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, s);
    }

    static final class LastInboundHandler extends ChannelDuplexHandler {
        private final Queue<Object> inboundMessages = new ArrayDeque<Object>();
        private final Queue<Object> userEvents = new ArrayDeque<Object>();
        private Throwable lastException;
        private ChannelHandlerContext ctx;
        private boolean channelActive;
        private String writabilityStates = "";

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            if (channelActive) {
                throw new IllegalStateException("channelActive may only be fired once.");
            }
            channelActive = true;
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (!channelActive) {
                throw new IllegalStateException("channelInactive may only be fired once after channelActive.");
            }
            channelActive = false;
            super.channelInactive(ctx);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            if (writabilityStates == "") {
                writabilityStates = String.valueOf(ctx.channel().isWritable());
            } else {
                writabilityStates += "," + ctx.channel().isWritable();
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            inboundMessages.add(msg);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            userEvents.add(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (lastException != null) {
                cause.printStackTrace();
            } else {
                lastException = cause;
            }
        }

        public void checkException() throws Exception {
            if (lastException == null) {
                return;
            }
            Throwable t = lastException;
            lastException = null;
            PlatformDependent.throwException(t);
        }

        @SuppressWarnings("unchecked")
        public <T> T readInbound() {
            return (T) inboundMessages.poll();
        }

        @SuppressWarnings("unchecked")
        public <T> T readUserEvent() {
            return (T) userEvents.poll();
        }

        public Channel channel() {
            return ctx.channel();
        }

        public void finishAndReleaseAll() throws Exception {
            checkException();
            Object o;
            while ((o = readInbound()) != null) {
                release(o);
            }
            while ((o = readUserEvent()) != null) {
                release(o);
            }
        }
    }
}
