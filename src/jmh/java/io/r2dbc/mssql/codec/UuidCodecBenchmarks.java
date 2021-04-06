/*
 * Copyright 2019-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.mssql.codec;

import io.netty.buffer.ByteBuf;
import io.r2dbc.mssql.message.token.Column;
import io.r2dbc.mssql.message.type.LengthStrategy;
import io.r2dbc.mssql.message.type.SqlServerType;
import io.r2dbc.mssql.message.type.TypeInformation;
import io.r2dbc.mssql.util.HexUtils;
import org.junit.platform.commons.annotation.Testable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.UUID;

/**
 * Benchmarks for {@code UuidCodec}.
 *
 * @author Mark Paluch
 */
@State(Scope.Thread)
@Testable
public class UuidCodecBenchmarks extends CodecBenchmarkSupport {

    private static final DefaultCodecs codecs = new DefaultCodecs();

    private static final Column column = new Column(0, "",
        TypeInformation.builder().withMaxLength(16).withLengthStrategy(LengthStrategy.FIXEDLENTYPE).withPrecision(16).withServerType(SqlServerType.GUID).build());

    private final ByteBuf buffer = HexUtils.decodeToByteBuf("F17B0DC7C7E5C54098C7A12F7E686724");

    private final UUID toEncode = UUID.fromString("C70D7BF1-E5C7-40C5-98C7-A12F7E686724");

    @Benchmark
    public Object decode() {
        this.buffer.readerIndex(0);
        return codecs.decode(this.buffer, column, UUID.class);
    }

    @Benchmark
    public Encoded encode() {
        return doEncode(this.toEncode);
    }

    @Benchmark
    public Encoded encodeNull() {
        Encoded encoded = codecs.encodeNull(alloc, UUID.class);
        encoded.release();
        return encoded;
    }

    private Encoded doEncode(Object value) {
        Encoded encoded = codecs.encode(alloc, RpcParameterContext.in(), value);
        encoded.release();
        return encoded;
    }
}
