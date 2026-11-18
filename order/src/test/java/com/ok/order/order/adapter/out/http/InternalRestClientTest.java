package com.ok.order.order.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.ok.common.exception.RestApiException;
import com.ok.common.trace.TraceContext;
import com.ok.order.order.exception.OrderErrorCode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class InternalRestClientTest {

    private HttpServer server;
    private final AtomicReference<Headers> received = new AtomicReference<>();
    // 테스트마다 응답 모양을 바꾼다. 기본은 본문 없는 200
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>(exchange -> {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    });

    // 응답 시간 초과를 짧게 둬 테스트를 빨리 끝낸다. 서버는 이보다 넉넉히 늦게 답한다
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final long LATE_MILLIS = 1_000L;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders());
            handler.get().handle(exchange);
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

    @Test
    void 상태_코드를_받기_전에_시간이_초과되면_실패_코드로_바꾼다() {
        // given: 요청 본문을 받고 처리하느라 늦게 답한다(3차 부하테스트의 재고 선점 33건).
        // 본문을 보내면 상태 코드를 보내기 구간 밖에서 읽어, 고치기 전에는 여기서 500이 됐다
        handler.set(exchange -> {
            sleep();
            respond(exchange, 200, "{}");
        });
        RestClient sut = InternalRestClient.create(baseUrl(), OrderErrorCode.PRODUCT_API_FAILED, READ_TIMEOUT);

        // when & then
        assertThatThrownBy(() -> sut.post().uri("/x").body(Map.of("orderId", 1)).retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_API_FAILED));
    }

    @Test
    void 본문을_읽다가_시간이_초과되면_실패_코드로_바꾼다() {
        // given: 상태 코드와 헤더는 바로 주고 본문은 늦게 준다(3차 부하테스트의 배송비 계산 1,000건)
        handler.set(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            sleep();
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊으면 쓰기가 실패한다
            }
        });
        RestClient sut = InternalRestClient.create(baseUrl(), OrderErrorCode.DELIVERY_API_FAILED, READ_TIMEOUT);

        // when & then
        assertThatThrownBy(() -> sut.post().uri("/x").retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { }))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.DELIVERY_API_FAILED));
    }

    @Test
    void 본문이_제때_오면_그대로_읽는다() {
        // given
        handler.set(exchange -> respond(exchange, 200, "{\"name\":\"사과\"}"));
        RestClient sut = InternalRestClient.create(baseUrl(), OrderErrorCode.PRODUCT_API_FAILED, READ_TIMEOUT);

        // when
        Map<String, Object> body = sut.get().uri("/x").retrieve().body(new ParameterizedTypeReference<>() { });

        // then
        assertThat(body).containsEntry("name", "사과");
    }

    @Test
    void 형식이_깨진_본문은_다시_해도_낫지_않아_실패_코드로_바꾸지_않는다() {
        // given
        handler.set(exchange -> respond(exchange, 200, "{not-json"));
        RestClient sut = InternalRestClient.create(baseUrl(), OrderErrorCode.PRODUCT_API_FAILED, READ_TIMEOUT);

        // when & then
        assertThatThrownBy(() -> sut.get().uri("/x").retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { }))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void 상태_처리기와_오류_처리기가_미리_읽은_본문을_읽을_수_있다() {
        // given: 상품의 409처럼 본문으로 이유를 가르는 응답
        handler.set(exchange -> respond(exchange, 409, "{\"type\":\"insufficient-stock\"}"));
        RestClient sut = InternalRestClient.create(baseUrl(), OrderErrorCode.PRODUCT_API_FAILED, READ_TIMEOUT);

        // when & then
        assertThatThrownBy(() -> sut.post().uri("/x").retrieve()
                .onStatus(status -> status.value() == 409, (request, response) -> {
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (body.contains("insufficient-stock")) {
                        throw new RestApiException(OrderErrorCode.OUT_OF_STOCK);
                    }
                })
                .toBodilessEntity())
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.OUT_OF_STOCK));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // 클라이언트가 시간 초과로 먼저 끊으면 쓰기가 실패한다
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(LATE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
