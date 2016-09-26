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

/**
 * Foo bar.
 */
public class Http2Stream2Exception extends Exception {

    private static final long serialVersionUID = -4407186173493887044L;

    private final Http2Error error;
    private final Http2Stream2<?> stream;

    public <T> Http2Stream2Exception(Http2Error error, String message, Throwable cause, Http2Stream2<T> stream) {
        super(message, cause);
        this.error = error;
        this.stream = stream;
    }

    public Http2Error error() {
        return error;
    }

    @SuppressWarnings("unchecked")
    public <T> Http2Stream2<T> stream() {
        return (Http2Stream2<T>) stream;
    }

}
