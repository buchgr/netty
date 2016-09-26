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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;

import static io.netty.buffer.Unpooled.buffer;
import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Test for {@link Http2ManagedStreamStateHandler}.
 */
public class DefaultHttp2ManagedStreamStateHandlerTest {

    private static final int SERVER_ID = 2;
    private static final int CLIENT_ID = 3;

    TestHandler testHandler;

    EmbeddedChannel channel;

    @Before
    public void setup() {
        testHandler = new TestHandler();
        channel = new EmbeddedChannel();
        channel.connect(new InetSocketAddress(0));
        channel.pipeline().addLast(testHandler);
        channel.runPendingTasks();

        testHandler.assertNoStreamsActive();
    }

    @After
    public void teardown() {
        assertNull(testHandler.readInbound());
        assertNull(testHandler.nextStreamActive());
        assertNull(testHandler.nextStreamClosed());

        testHandler.assertNoStreamsActive();

        testHandler = null;
    }

    @Test
    public void streamStateShouldBeCleanedUp_endStream() {
        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).setStreamId(CLIENT_ID);
        channel.writeInbound(headersFrame);
        DefaultHttp2DataFrame dataFrame =
                new DefaultHttp2DataFrame(buffer().writeZero(10), true).setStreamId(CLIENT_ID);
        channel.writeInbound(dataFrame);

        testHandler.assertStreamActive(CLIENT_ID);
        // Received a client stream. so we are the server.
        testHandler.assertServerMode();

        assertSame(headersFrame, testHandler.nextStreamActive());
        assertEquals(dataFrame, testHandler.readInbound());
        ReferenceCountUtil.release(dataFrame);

        headersFrame = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers(), true).setStreamId(CLIENT_ID);
        channel.writeAndFlush(headersFrame);

        assertEquals(headersFrame, channel.readOutbound());

        ManagedState state = testHandler.nextStreamClosed();
        assertNotNull(state);
        assertEquals(CLIENT_ID, state.streamId);
    }

    @Test
    public void streamStateShouldBeCleanedUp_outboundResetStream() {
        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).setStreamId(SERVER_ID);
        channel.writeInbound(headersFrame);
        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(buffer().writeZero(10), true).setStreamId(SERVER_ID);
        channel.writeInbound(dataFrame1);
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(buffer().writeZero(20), true).setStreamId(SERVER_ID);
        channel.writeInbound(dataFrame2);

        testHandler.assertStreamActive(SERVER_ID);
        // Received a server stream, so we are the client.
        testHandler.assertClientMode();

        assertEquals(headersFrame, testHandler.nextStreamActive());
        assertEquals(dataFrame1, testHandler.readInbound());
        ReferenceCountUtil.release(dataFrame1);
        assertEquals(dataFrame2, testHandler.readInbound());
        ReferenceCountUtil.release(dataFrame2);

        Http2ResetFrame rstFrame = new DefaultHttp2ResetFrame(INTERNAL_ERROR).setStreamId(SERVER_ID);
        channel.writeAndFlush(rstFrame);

        assertEquals(rstFrame, channel.readOutbound());

        ManagedState state = testHandler.nextStreamClosed();
        assertNotNull(state);
        assertEquals(SERVER_ID, state.streamId);
    }

    @Test
    public void streamStateShouldBeCleanedUp_inboundResetStream() {
        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).setStreamId(SERVER_ID);
        channel.write(headersFrame);
        Http2DataFrame dataFrame = new DefaultHttp2DataFrame(buffer().writeZero(10), true).setStreamId(SERVER_ID);
        channel.writeAndFlush(dataFrame);

        assertEquals(headersFrame, channel.readOutbound());
        assertEquals(dataFrame, channel.readOutbound());

        testHandler.assertStreamActive(SERVER_ID);
        // We wrote a server stream, so we are the server.
        testHandler.assertServerMode();

        assertEquals(headersFrame, testHandler.nextStreamActive());

        Http2ResetFrame rstFrame = new DefaultHttp2ResetFrame(INTERNAL_ERROR).setStreamId(SERVER_ID);
        channel.writeAndFlush(rstFrame);

        assertEquals(rstFrame, channel.readOutbound());

        ManagedState state = testHandler.nextStreamClosed();
        assertNotNull(state);
        assertEquals(SERVER_ID, state.streamId);
    }


    static final class TestHandler extends Http2ManagedStreamStateHandler<ManagedState> {

        Queue<Http2Frame> frames = new ArrayDeque<Http2Frame>();

        Queue<Http2HeadersFrame> streamActives = new ArrayDeque<Http2HeadersFrame>();

        Queue<Object> streamClosed = new ArrayDeque<Object>();

        Http2HeadersFrame nextStreamActive() {
            return streamActives.poll();
        }

        @SuppressWarnings("unchecked")
        <T> T nextStreamClosed() {
            return (T) streamClosed.poll();
        }

        @SuppressWarnings("unchecked")
        <T extends Http2Frame> T readInbound() {
            return (T) frames.poll();
        }

        void assertNoStreamsActive() {
            assertTrue(activeStreams.isEmpty());
        }

        void assertStreamActive(int streamId) {
            assertTrue(activeStreams.containsKey(streamId));
        }

        void assertServerMode() {
            assertEquals(0, endPointMode);
        }

        void assertClientMode() {
            assertEquals(1, endPointMode);
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http2StreamFrame frame, ManagedState managedState)
                throws Exception {
            frames.add(frame);
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http2Frame frame) throws Exception {
            frames.add(frame);
        }

        @Override
        protected ManagedState onStreamActive(ChannelHandlerContext ctx, int streamId, Http2HeadersFrame firstHeaders)
                throws Exception {
            assertEquals(streamId, firstHeaders.getStreamId());

            streamActives.add(firstHeaders);

            return new ManagedState(streamId);
        }

        @Override
        protected void onStreamClosed(ChannelHandlerContext ctx, int streamId, ManagedState managedState) {
            if (managedState != null) {
                assertEquals(streamId, managedState.streamId);

                streamClosed.add(managedState);
            } else {
                streamClosed.add(new NullState(streamId));
            }
        }
    }

    static class ManagedState {

        int streamId;

        ManagedState(int streamId) {
            this.streamId = streamId;
        }
    }

    static class NullState {

        int streamId;

        NullState(int streamId) {
            this.streamId = streamId;
        }
    }
}
