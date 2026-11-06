package com.ok.order.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import com.ok.common.trace.TraceContext;
import com.ok.order.order.exception.OrderErrorCode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class InternalRestClientTest {

    private HttpServer server;
    private final AtomicReference<Headers> received = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 추적_범위_안의_호출은_추적_헤더를_보낸다() {
        // given
        RestClient sut = InternalRestClient.create("http://127.0.0.1:" + server.getAddress().getPort(),
                OrderErrorCode.PRODUCT_API_FAILED);

        // when
        try (TraceContext.Scope ignored = TraceContext.open("111")) {
            TraceContext.bindOrderId(222L);
            sut.get().uri("/x").retrieve().toBodilessEntity();
        }

        // then
        Headers headers = received.get();
        assertThat(headers).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("111");
            softly.assertThat(headers.getFirst("X-Order-Id")).isEqualTo("222");
            softly.assertThat(headers.getFirst("X-Shipment-Id")).isNull();
        });
    }
}
