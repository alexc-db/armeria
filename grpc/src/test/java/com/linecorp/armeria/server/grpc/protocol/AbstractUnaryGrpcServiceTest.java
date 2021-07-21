/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc.protocol;

import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.RESPONSE_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class AbstractUnaryGrpcServiceTest {
    private static final byte TRAILERS_FRAME_HEADER = (byte) (1 << 7);
    private static final byte[] SERIALIZED_TRAILER_STATUS = "grpc-status: 0\r\n".getBytes(
            StandardCharsets.US_ASCII);
    private static final byte[] SERIALIZED_TRAILERS = Bytes.concat(
            new byte[] { TRAILERS_FRAME_HEADER },
            Ints.toByteArray(SERIALIZED_TRAILER_STATUS.length),
            SERIALIZED_TRAILER_STATUS);
    private static final String METHOD_NAME = "/armeria.grpc.testing.TestService/UnaryCall";
    private static final String PAYLOAD_BODY = "hello";
    private static final SimpleRequest REQUEST_MESSAGE = SimpleRequest.newBuilder().setPayload(
            Payload.newBuilder().setBody(ByteString.copyFromUtf8(PAYLOAD_BODY)).build()).build();
    private static final SimpleResponse RESPONSE_MESSAGE = SimpleResponse.newBuilder().setPayload(
            Payload.newBuilder().setBody(ByteString.copyFromUtf8(PAYLOAD_BODY)).build()).build();

    // This service only depends on protobuf. Users can use a custom decoder / encoder to avoid even that.
    private static class TestService extends AbstractUnaryGrpcService {

        @Override
        protected CompletionStage<byte[]> handleMessage(ServiceRequestContext ctx, byte[] message) {
            assertThat(ServiceRequestContext.currentOrNull()).isSameAs(ctx);

            final SimpleRequest request;
            try {
                request = SimpleRequest.parseFrom(message);
            } catch (InvalidProtocolBufferException e) {
                throw new UncheckedIOException(e);
            }
            assertThat(request).isEqualTo(REQUEST_MESSAGE);
            return CompletableFuture.completedFuture(RESPONSE_MESSAGE.toByteArray());
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(METHOD_NAME, new TestService());
        }
    };

    @Test
    void normalDownstream() {
        final TestServiceBlockingStub stub =
                Clients.newClient(server.httpUri(GrpcSerializationFormats.PROTO),
                                  TestServiceBlockingStub.class);
        final SimpleResponse response = stub.unaryCall(REQUEST_MESSAGE);
        assertThat(response).isEqualTo(RESPONSE_MESSAGE);
    }

    @Test
    void normalUpstream() {
        final ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                                            .usePlaintext()
                                                            .build();
        try {
            final TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);
            final SimpleResponse response = stub.unaryCall(REQUEST_MESSAGE);
            assertThat(response).isEqualTo(RESPONSE_MESSAGE);
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void unsupportedMediaType() {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response =
                client.post(METHOD_NAME, "foobarbreak").aggregate().join();

        assertThat(response.headers().get(HttpHeaderNames.STATUS)).isEqualTo(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.codeAsText());
    }

    @Test
    void invalidPayload() {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse message =
                client.prepare().post(METHOD_NAME).content(
                        GrpcSerializationFormats.PROTO.mediaType(), "foobarbreak").execute().aggregate().join();

        // Trailers-Only response.
        assertThat(message.headers().get(HttpHeaderNames.STATUS)).isEqualTo(HttpStatus.OK.codeAsText());
        assertThat(message.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/grpc+proto");
        assertThat(message.headers().get(GrpcHeaderNames.GRPC_STATUS))
                .isEqualTo(Integer.toString(StatusCodes.INTERNAL));
        assertThat(message.headers().get(GrpcHeaderNames.GRPC_MESSAGE)).isNotBlank();
        assertThat(message.content().isEmpty()).isEqualTo(true);
    }

    @Test
    void grpcWeb() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders
                        .of(HttpMethod.POST, METHOD_NAME, HttpHeaderNames.CONTENT_TYPE, "application/grpc-web"),
                GrpcTestUtil.uncompressedFrame(GrpcTestUtil.protoByteBuf(REQUEST_MESSAGE))).aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                "application/grpc-web+proto");
        assertThat(response.content().array()).containsExactly(
                Bytes.concat(
                        GrpcTestUtil.uncompressedFrame(
                                GrpcTestUtil.protoByteBuf(RESPONSE_MESSAGE)),
                        SERIALIZED_TRAILERS));
    }

    @Test
    void grpcWebText() {
        final WebClient client = WebClient.of(server.httpUri());
        final byte[] body = GrpcTestUtil.uncompressedFrame(GrpcTestUtil.protoByteBuf(REQUEST_MESSAGE));
        final HttpResponse httpResponse = client.execute(
                RequestHeaders.of(HttpMethod.POST, METHOD_NAME, HttpHeaderNames.CONTENT_TYPE,
                                  "application/grpc-web-text"),
                Base64.getEncoder().encode(body));
        final AggregatedHttpResponse response = httpResponse.mapData(data -> {
            final ByteBuf buf = data.byteBuf();
            final ByteBuf decoded = Unpooled.wrappedBuffer(
                    Base64.getDecoder().decode(buf.nioBuffer()));
            buf.release();
            return HttpData.wrap(decoded);
        }).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                "application/grpc-web-text+proto");
        assertThat(response.content().array()).containsExactly(
                Bytes.concat(
                        GrpcTestUtil.uncompressedFrame(
                                GrpcTestUtil.protoByteBuf(RESPONSE_MESSAGE)),
                        SERIALIZED_TRAILERS));
    }
}
