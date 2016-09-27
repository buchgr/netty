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
 * Created by buchgr on 9/26/16.
 */
public class Http2Stream2<T> {

    public static final Http2Stream2<Void> CONNECTION_STREAM = new Http2Stream2<Void>() {
        @Override
        Http2Stream2<Void> id(int id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Void managedState() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void managedState(Void state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int id() {
            return 0;
        }
    };

    private T managedState;
    private int id = -1;

    public Http2Stream2() {
    }

    public T managedState() {
        return managedState;
    }

    public void managedState(T state) {
        managedState = state;
    }

    public int id() {
        return id;
    }

    Http2Stream2<T> id(int id) {
        this.id = id;
        return this;
    }
}
