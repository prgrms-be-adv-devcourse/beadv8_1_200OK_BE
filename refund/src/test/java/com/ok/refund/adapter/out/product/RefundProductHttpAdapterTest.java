package com.ok.refund.adapter.out.product;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.exception.RefundErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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

    // 상품의 거절(404·409)은 다시 해도 결과가 같아, 다시 시도하지 않을 코드로 바꾼다(#576).
    // 409만 돈 다시 가져오기(#546) 대상인 PRODUCT_RELEASE_REFUSED이고, 404는 주소 설정 오류와 가를 수 없어 따로 둔다
    @ParameterizedTest
    @MethodSource("다시_해도_같은_상태")
    void 다시_해도_같은_거절은_상태마다_다른_코드로_바꾼다(int errorStatus, RefundErrorCode expected) {
        // given
        status.set(errorStatus);

        // when & then
        assertThatThrownBy(() -> sut.releaseStock(new StockRelease(7L, 10L, 55L, 1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    static Stream<Arguments> 다시_해도_같은_상태() {
        return Stream.of(
                Arguments.of(409, RefundErrorCode.PRODUCT_RELEASE_REFUSED),
                Arguments.of(404, RefundErrorCode.PRODUCT_STOCK_NOT_FOUND));
    }

    // 422는 우리가 보낸 값이 틀린 것(환불 버그)이라 상품의 거절로 섞지 않는다. 섞으면 돈 다시 가져오기(#546)가 우리 버그로 구매자 돈을 회수한다
    @Test
    void 요청_값_오류_422는_거절이_아니라_표준_예외() {
        // given
        status.set(422);

        // when & then
        assertThatThrownBy(() -> sut.releaseStock(new StockRelease(7L, 10L, 55L, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("상품이 재고 해제 요청 값을 거절함(422, 환불이 보낸 값의 오류). refundId=7, orderId=10");
    }

    // 서버 오류, 문서에 없는 4xx(설정 문제로 막힘·요청 과다 등)는 고치면 풀리거나 잠깐의 실패일 수 있어 다시 시도할 코드 그대로 둔다
    @ParameterizedTest
    @MethodSource("다시_시도할_상태")
    void 서버_오류나_문서에_없는_4xx면_PRODUCT_API_FAILED(int errorStatus) {
        // given
        status.set(errorStatus);

        // when & then
        assertThatThrownBy(() -> sut.releaseStock(new StockRelease(7L, 10L, 55L, 1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PRODUCT_API_FAILED));
    }

    static Stream<Integer> 다시_시도할_상태() {
        return Stream.of(500, 503, 401, 403, 408, 429);
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
