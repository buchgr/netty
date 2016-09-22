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

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder.*;
import static io.netty.handler.codec.http2.DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO;
import static io.netty.util.internal.ObjectUtil.*;

/**
 * Builder for {@link Http2FrameCodec}.
 */
@UnstableApi
public final class Http2FrameCodecBuilder {

    private final boolean server;
    private Http2FrameWriter frameWriter;
    private Http2FrameReader frameReader;
    private Http2Settings initialSettings;
    private long gracefulShutdownTimeoutMillis;
    private float windowUpdateRatio;
    private Http2FrameLogger frameLogger;
    private boolean bufferOutgoingStreams;

    private Http2FrameCodecBuilder(boolean server) {
        this.server = server;
        frameWriter = new DefaultHttp2FrameWriter();
        frameReader = new DefaultHttp2FrameReader();
        initialSettings = new Http2Settings();
        gracefulShutdownTimeoutMillis = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS;
        windowUpdateRatio = DEFAULT_WINDOW_UPDATE_RATIO;
    }

    public static Http2FrameCodecBuilder forClient() {
        return new Http2FrameCodecBuilder(false);
    }

    public static Http2FrameCodecBuilder forServer() {
        return new Http2FrameCodecBuilder(true);
    }

    public Http2FrameCodecBuilder frameWriter(Http2FrameWriter frameWriter) {
        this.frameWriter = checkNotNull(frameWriter, "frameWriter");
        return this;
    }

    public Http2FrameCodecBuilder frameReader(Http2FrameReader frameReader) {
        this.frameReader = checkNotNull(frameReader, "frameReader");
        return this;
    }

    public Http2FrameCodecBuilder initialSettings(Http2Settings initialSettings) {
        this.initialSettings = checkNotNull(initialSettings, "initialSettings");
        return this;
    }

    public Http2FrameCodecBuilder gracefulShutdownTimeout(long timeout, TimeUnit unit) {
        gracefulShutdownTimeoutMillis =
                checkNotNull(unit, "unit").toMillis(checkPositiveOrZero(timeout, "timeout"));
        return this;
    }

    public Http2FrameCodecBuilder windowUpdateRatio(float windowUpdateRatio) {
        if (windowUpdateRatio <= Float.compare(windowUpdateRatio, 0) ||
            windowUpdateRatio >= Float.compare(windowUpdateRatio, 1)) {
            throw new IllegalArgumentException("windowUpdateRatio must be (0,1). Was: " + windowUpdateRatio);
        }
        this.windowUpdateRatio = windowUpdateRatio;
        return this;
    }

    public Http2FrameCodecBuilder frameLogger(Http2FrameLogger frameLogger) {
        this.frameLogger = checkNotNull(frameLogger, "frameLogger");
        return this;
    }

    public Http2FrameCodecBuilder bufferOutgoingStreams(boolean bufferOutgoingStreams) {
        this.bufferOutgoingStreams = bufferOutgoingStreams;
        return this;
    }

    public Http2FrameCodec build() {
        Http2Connection connection = new DefaultHttp2Connection(server);

        if (frameLogger != null) {
            frameWriter = new Http2OutboundFrameLogger(frameWriter, frameLogger);
            frameReader = new Http2InboundFrameLogger(frameReader, frameLogger);
        }

        Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(connection, frameWriter);

        if (bufferOutgoingStreams) {
            encoder = new StreamBufferingEncoder(encoder);
        }

        connection.local().flowController(new DefaultHttp2LocalFlowController(connection, windowUpdateRatio,
                                                                              true /* auto refill conn window */));

        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder, frameReader);

        return new Http2FrameCodec(encoder, decoder, initialSettings, gracefulShutdownTimeoutMillis);
    }
}
