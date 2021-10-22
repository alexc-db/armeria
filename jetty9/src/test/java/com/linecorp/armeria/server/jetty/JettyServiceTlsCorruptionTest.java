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
package com.linecorp.armeria.server.jetty;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;


class JettyServiceTlsCorruptionTest {

    private static final Logger logger = LoggerFactory.getLogger(JettyServiceTlsCorruptionTest.class);

    private static final HttpData content;

    static {
        final byte[] data = new byte[1048576];
        ThreadLocalRandom.current().nextBytes(data);
        content = HttpData.wrap(data);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.https(0);
            sb.tlsSelfSigned();

            // With JettyService
            sb.service("/with-jetty", newJettyService((req, res) -> {
                res.setStatus(200);
                res.getOutputStream().write(content.array());
            }));

            // Without JettyService
            sb.service("/without-jetty", (ctx, req) -> {
                final HttpResponseWriter res = HttpResponse.streaming();
                res.write(ResponseHeaders.of(200));
                for (int i = 0; i < content.length();) {
                    final int chunkSize = Math.min(32768, content.length() - i);
                    res.write(HttpData.wrap(content.array(), i, chunkSize));
                    i += chunkSize;
                }
                res.close();
                return res;
            });
        }
    };

    @ParameterizedTest
    @CsvSource({ "H1, /without-jetty", "H1, /with-jetty" })
    void test(SessionProtocol protocol, String path) throws Throwable {
        final int numEventLoops = Flags.numCommonWorkers();
        final ClientFactory clientFactory = ClientFactory.builder()
                .tlsNoVerify()
                .maxNumEventLoopsPerEndpoint(numEventLoops)
                .maxNumEventLoopsPerHttp1Endpoint(numEventLoops)
                .build();
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .factory(clientFactory)
                                          .build();
        final Semaphore semaphore = new Semaphore(numEventLoops);
        final BlockingQueue<Throwable> caughtExceptions = new LinkedBlockingDeque<>();
        int i = 0;
        try {
            for (; i < 1000; i++) {
                semaphore.acquire();
                client.get(path)
                      .aggregate()
                      .handle((res, cause) -> {
                          semaphore.release();
                          if (cause != null) {
                              caughtExceptions.add(cause);
                          }
                          try {
                              assertThat(res.status()).isSameAs(HttpStatus.OK);
                              assertThat(res.content()).isEqualTo(content);
                          } catch (Throwable t) {
                              caughtExceptions.add(t);
                          }
                          return null;
                      });

                final Throwable cause = caughtExceptions.poll();
                if (cause != null) {
                    throw cause;
                }
            }
        } catch (Throwable cause) {
            logger.warn("Received a corrupt response after {} request(s)", i);
            throw cause;
        }
    }

    private static JettyService newJettyService(SimpleHandlerFunction func) {
        return JettyService.builder()
                           .handler(new AbstractHandler() {
                               @Override
                               public void handle(String target, Request baseRequest,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) throws ServletException {
                                   try {
                                       func.handle(baseRequest, (Response) response);
                                   } catch (Throwable t) {
                                       throw new ServletException(t);
                                   }
                               }
                           })
                           .build();
    }

    @FunctionalInterface
    private interface SimpleHandlerFunction {
        void handle(Request req, Response res) throws Throwable;
    }
}
