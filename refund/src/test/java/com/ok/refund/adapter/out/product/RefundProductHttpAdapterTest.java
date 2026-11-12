package com.ok.refund.adapter.out.product;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.exception.RefundErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 상품 재고 일부 해제 요청의 경로와 필드 이름이 상품 API(#363)와의 약속이다. 이름이 어긋나면 컴파일은 되고
// 상품 쪽에서 값이 비어 400이 나므로, 실제로 나가는 요청을 JDK 내장 서버로 받아 확인한다
class RefundProductHttpAdapterTest {

    private final AtomicInteger status = new AtomicInteger(204);
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private RefundProductHttpAdapter sut;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            method.set(exchange.getRequestMethod());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        sut = new RefundProductHttpAdapter("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 재고_복구는_상품_API_경로로_refundId와_옵션과_개수를_보낸다() {
        // given: 상품 API는 성공하면 본문 없이 204로 답한다
        status.set(204);

        // when
        sut.releaseStock(new StockRelease(7L, 10L, 55L, 2));

        // then: 주문 ID는 경로로, 나머지는 본문으로 간다
        assertSoftly(softly -> {
            softly.assertThat(method.get()).isEqualTo("POST");
            softly.assertThat(path.get()).isEqualTo("/internal/v1/products/stock-allocations/10/partial-release");
            softly.assertThat(requestBody.get()).isEqualToIgnoringWhitespace(
                    "{\"refundId\":7,\"productOptionId\":55,\"quantity\":2}");
        });
    }

    // 409(해제 가능 수량 초과, 같은 refundId인데 내용이 다름)도 일시적 실패와 같은 코드다. 구분은 #516
    @Test
    void 재고_복구가_오류_상태를_받으면_PRODUCT_API_FAILED() {
        // given
        status.set(409);

        // when & then
        assertThatThrownBy(() -> sut.releaseStock(new StockRelease(7L, 10L, 55L, 1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PRODUCT_API_FAILED));
    }

    @Test
    void 상품_서버가_꺼져_있으면_PRODUCT_API_FAILED() {
        // given
        server.stop(0);

        // when & then
        assertThatThrownBy(() -> sut.releaseStock(new StockRelease(7L, 10L, 55L, 1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PRODUCT_API_FAILED));
    }
}
