///*
// * Copyright 2016 The Netty Project
// *
// * The Netty Project licenses this file to you under the Apache License,
// * version 2.0 (the "License"); you may not use this file except in compliance
// * with the License. You may obtain a copy of the License at:
// *
// *   http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// * License for the specific language governing permissions and limitations
// * under the License.
// */
//
//package io.netty.handler.codec.http2;
//
//import io.netty.buffer.ByteBuf;
//import io.netty.buffer.Unpooled;
//import io.netty.channel.ChannelHandlerContext;
//import io.netty.channel.embedded.EmbeddedChannel;
//import io.netty.util.ReferenceCountUtil;
//import org.junit.Before;
//import org.junit.Test;
//
//import java.net.InetSocketAddress;
//import java.util.ArrayDeque;
//import java.util.Queue;
//
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertNotNull;
//import static org.junit.Assert.assertTrue;
//
///**
// * Tests for {@link Http2ManagedStreamStateHandler}.
// */
//public class Http2ManagedStreamStateHandlerTest {
//
//    private static final int CLIENT_ID = 1;
//    private static final int SERVER_ID = 2;
//
//    private EmbeddedChannel channel;
//
//    private TestHandler testHandler;
//
//    @Before
//    public void setup() {
//        channel = new EmbeddedChannel();
//        channel.connect(new InetSocketAddress(0));
//        testHandler = new TestHandler();
//        channel.pipeline().addLast(testHandler);
//    }
//
//    @Test
//    public void endStreamShouldCloseStream() {
//        assertNoActiveStreams();
//
//        channel.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers(), true).setStreamId(CLIENT_ID));
//
//        assertActiveStream(CLIENT_ID);
//        // Remote stream is a client stream, so we are the server.
//        assertServerMode();
//
//        Http2HeadersFrame headersFrame = testHandler.readStreamFrame();
//        assertEquals(CLIENT_ID, headersFrame.getStreamId());
//
//        channel.writeOutbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers(), true).setStreamId(CLIENT_ID));
//
//        assertNoActiveStreams();
//    }
//
//    @Test
//    public void resetStreamShouldCloseStream_inbound() {
//        assertNoActiveStreams();
//
//        channel.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).setStreamId(SERVER_ID));
//        ByteBuf data = Unpooled.buffer().writeZero(100);
//        channel.writeInbound(new DefaultHttp2DataFrame(ReferenceCountUtil.releaseLater(data.retain()))
//                                     .setStreamId(SERVER_ID));
//
//        assertActiveStream(SERVER_ID);
//        // Remote stream is a server stream, so we are the client.
//        assertClientMode();
//
//        Http2HeadersFrame headersFrame = testHandler.readStreamFrame();
//        assertEquals(SERVER_ID, headersFrame.getStreamId());
//        Http2DataFrame dataFrame = testHandler.readStreamFrame();
//        assertEquals(SERVER_ID, dataFrame.getStreamId());
//
//        channel.writeOutbound(new DefaultHttp2ResetFrame(Http2Error.INTERNAL_ERROR).setStreamId(SERVER_ID));
//
//        assertNoActiveStreams();
//    }
//
//    @Test
//    public void resetStreamShouldCloseStream_outbound() {
//        assertNoActiveStreams();
//
//        channel.writeOutbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
//
//        // What the Http2FrameCodec would respond.
//        Http2HeadersFrame headersFrame = channel.readOutbound();
//        assertNotNull(headersFrame);
//        channel.pipeline().fireUserEventTriggered(new Http2OutgoingStreamActive(CLIENT_ID, 100, headersFrame));
//
//        assertActiveStream(CLIENT_ID);
//        // We created the stream, so we are the client.
//        assertClientMode();
//
//        channel.writeInbound(new DefaultHttp2ResetFrame(Http2Error.INTERNAL_ERROR).setStreamId(CLIENT_ID));
//
//        assertNoActiveStreams();
//    }
//
//    private void assertActiveStream(int streamId) {
//        assertTrue(testHandler.activeStreams.containsKey(streamId));
//    }
//
//    private void assertNoActiveStreams() {
//        assertTrue(testHandler.activeStreams.isEmpty());
//    }
//
//    private void assertServerMode() {
//        assertEquals(0, testHandler.endPointMode);
//    }
//
//    private void assertClientMode() {
//        assertEquals(1, testHandler.endPointMode);
//    }
//
//    private static final class TestHandler extends Http2ManagedStreamStateHandler<TestState> {
//
//        final Queue<Http2StreamFrame> streamFrames = new ArrayDeque<Http2StreamFrame>();
//        final Queue<Http2Frame> connectionFrames = new ArrayDeque<Http2Frame>();
//
//        @SuppressWarnings("unchecked")
//        <T extends Http2StreamFrame> T readStreamFrame() {
//            return (T) streamFrames.poll();
//        }
//
//        @SuppressWarnings("unchecked")
//        <T extends Http2Frame> T readConnectionFrame() {
//            return (T) connectionFrames.poll();
//        }
//
//        @Override
//        protected void channelRead(ChannelHandlerContext ctx, Http2StreamFrame frame, TestState streamInfo) {
//            assertNotNull(streamInfo);
//            assertEquals(frame.getStreamId(), streamInfo.streamId);
//            streamFrames.add(frame);
//        }
//
//        @Override
//        protected void channelRead(ChannelHandlerContext ctx, Http2Frame frame) {
//            connectionFrames.add(frame);
//        }
//
//        @Override
//        protected TestState onStreamActive(int streamId, Http2HeadersFrame firstHeaders) {
//            return new TestState(streamId);
//        }
//
//        @Override
//        protected void onStreamClosed(TestState managedState) {
//
//        }
//    }
//
//    private static final class TestState {
//        private final int streamId;
//
//        TestState(int streamId) {
//            this.streamId = streamId;
//        }
//    }
//}
