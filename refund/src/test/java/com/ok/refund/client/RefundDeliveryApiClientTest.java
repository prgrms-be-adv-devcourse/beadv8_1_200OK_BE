package com.ok.refund.client;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.dto.ShipmentReserveResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 배송은 404(배송 건 없음)와 409(잠금 거절)를 오류가 아닌 답으로 돌려준다.
// RestClient의 onStatus를 예외 없이 지나가게 둔 처리가 실제 HTTP 응답에서 그렇게 동작하는지 JDK 내장 서버로 확인한다
class RefundDeliveryApiClientTest {

    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;
    private RefundDeliveryApiClient sut;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        sut = new RefundDeliveryApiClient("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 배송_건이_있으면_true() {
        // given
        status.set(200);

        // when & then
        assertThat(sut.hasShipment(101L)).isTrue();
    }

    @Test
    void 배송_건_조회가_404면_false() {
        // given
        status.set(404);

        // when & then
        assertThat(sut.hasShipment(101L)).isFalse();
    }

    @Test
    void 잠금_요청이_409면_거절로_돌려준다() {
        // given
        status.set(409);

        // when & then
        assertThat(sut.reserveCancel(7L, 101L, 1)).isEqualTo(ShipmentReserveResult.REJECTED);
    }

    @Test
    void 잠금_요청이_200이면_잠김으로_돌려준다() {
        // given
        status.set(200);

        // when & then
        assertThat(sut.reserveCancel(7L, 101L, 1)).isEqualTo(ShipmentReserveResult.RESERVED);
    }

    @Test
    void 잠금_요청이_500이면_배송_호출_실패() {
        // given
        status.set(500);

        // when & then
        assertThatThrownBy(() -> sut.reserveCancel(7L, 101L, 1))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.DELIVERY_API_FAILED));
    }

    @Test
    void 서버가_꺼져_있으면_배송_호출_실패() {
        // given
        server.stop(0);

        // when & then
        assertThatThrownBy(() -> sut.hasShipment(101L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.DELIVERY_API_FAILED));
    }
}
