package com.ok.refund.adapter.out.order;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationResult;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 주문 응답의 경로와 필드 이름이 주문 API(조회 #533, 취소 반영 #542)와의 약속이다. 이름이 어긋나면 컴파일은 되고
// 값만 0이나 null로 들어와 조용히 틀리므로, 같은 모양의 JSON이 그대로 옮겨 담기는지 JDK 내장 서버로 확인한다
class RefundOrderHttpAdapterTest {

    private static final String ITEMS_JSON = """
            [ { "orderItemId": 101, "unitPrice": 10000, "quantity": 3 },
                { "orderItemId": 102, "unitPrice": 5000, "quantity": 1 } ]""";

    // 요청서 ① 응답 전체 모양. 테스트마다 칸 하나만 바꿔 쓴다
    private static final String VALID_BODY = """
            {
              "orderId": 10, "orderItemId": 101,
              "buyerId": 1, "sellerId": 5,
              "productOptionId": 55,
              "productName": "감자", "optionName": "1kg",
              "status": "PREPARING",
              "unitPrice": 10000, "quantity": 3, "refundedQuantity": 1,
              "sellerGroup": {
                "shippingFee": 1000,
                "remoteAreaFee": 2000,
                "baseShippingFee": 3000,
                "freeShippingThreshold": 50000,
                "items": %s
              }
            }
            """.formatted(ITEMS_JSON);

    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private RefundOrderHttpAdapter sut;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            method.set(exchange.getRequestMethod());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        sut = new RefundOrderHttpAdapter("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 요청서_모양의_주문_응답을_그대로_옮겨_담는다() {
        // given: 요청서 전체 모양. 받지 않는 칸(refundedQuantity)이 와도 깨지지 않아야 한다.
        // 묶음 금액 칸은 같은 long이라 뒤바뀌어도 컴파일이 되므로 칸마다 다른 값을 넣는다
        body.set(VALID_BODY);

        // when
        RefundableOrderItem orderItem = sut.getOrderItem(10L, 101L);

        // then
        assertThat(orderItem).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(path.get()).isEqualTo("/internal/v1/orders/10/items/101");
            softly.assertThat(orderItem.orderId()).isEqualTo(10L);
            softly.assertThat(orderItem.orderItemId()).isEqualTo(101L);
            softly.assertThat(orderItem.buyerId()).isEqualTo(1L);
            softly.assertThat(orderItem.sellerId()).isEqualTo(5L);
            softly.assertThat(orderItem.productOptionId()).isEqualTo(55L);
            softly.assertThat(orderItem.productName()).isEqualTo("감자");
            softly.assertThat(orderItem.optionName()).isEqualTo("1kg");
            softly.assertThat(orderItem.status()).isEqualTo("PREPARING");
            softly.assertThat(orderItem.unitPrice()).isEqualTo(10_000L);
            softly.assertThat(orderItem.quantity()).isEqualTo(3);
        });
    }

    @Test
    void 묶음_배송비_값을_모두_옮긴다() {
        // given
        body.set(VALID_BODY);

        // when
        BundleShipping bundle = sut.getOrderItem(10L, 101L).bundle();

        // then
        assertThat(bundle).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(bundle.shippingFee()).isEqualTo(1_000L);
            softly.assertThat(bundle.remoteAreaFee()).isEqualTo(2_000L);
            softly.assertThat(bundle.baseShippingFee()).isEqualTo(3_000L);
            softly.assertThat(bundle.freeShippingThreshold()).isEqualTo(50_000L);
            softly.assertThat(bundle.items()).containsExactly(
                    new BundleItem(101L, 10_000L, 3),
                    new BundleItem(102L, 5_000L, 1));
        });
    }

    @Test
    void 무료배송_기준이_없으면_null로_옮긴다() {
        // given
        body.set(VALID_BODY.replace("\"freeShippingThreshold\": 50000", "\"freeShippingThreshold\": null"));

        // when & then
        assertThat(sut.getOrderItem(10L, 101L).bundle().freeShippingThreshold()).isNull();
    }

    @Test
    void 묶음이_없으면_주문_쪽_버그로_표준_예외를_던진다() {
        // given: 0원 배송비로 채워 넘기면 돌려줄 배송비를 조용히 빠뜨린다
        body.set(VALID_BODY.substring(0, VALID_BODY.indexOf(",\n  \"sellerGroup\"")) + "\n}");

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 상품 조회 응답에 판매자 묶음이 없습니다. orderId=10, orderItemId=101");
    }

    @Test
    void 묶음의_상품_목록이_없으면_표준_예외를_던진다() {
        // given
        body.set(VALID_BODY.replace(ITEMS_JSON, "null"));

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 상품 조회 응답의 판매자 묶음에 상품 목록이 없습니다.");
    }

    @Test
    void 묶음_금액_칸이_빠지면_0원으로_채우지_않고_예외를_던진다() {
        // given: 기본 배송비 칸이 없는 응답
        body.set(VALID_BODY.replace("\"baseShippingFee\": 3000,", ""));

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void 묶음_금액이_음수면_표준_예외를_던진다() {
        // given
        body.set(VALID_BODY.replace("\"shippingFee\": 1000", "\"shippingFee\": -1"));

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 배송비·도서산간 운임·기본 배송비는 0 이상이어야 합니다.");
    }

    @Test
    void 주문이_404면_주문_상품_없음으로_바꾼다() {
        // given
        status.set(404);

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    @Test
    void 주문이_500이면_호출_실패로_바꾼다() {
        // given
        status.set(500);

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
    }

    @Test
    void 성공인데_본문이_비면_주문_쪽_버그로_표준_예외를_던진다() {
        // given: 상태 200, 본문 없음
        body.set("");

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 상품 조회 응답 본문이 비었습니다. orderId=10, orderItemId=101");
    }

    @Test
    void 본문이_깨졌으면_에러_코드로_바꾸지_않는다() {
        // given: 주문 쪽 버그라 503(잠시 후 다시 시도)이 아니라 500으로 드러나야 한다
        body.set("not json");

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void 금액_칸이_빠지면_0원으로_채우지_않고_예외를_던진다() {
        // given: 단가 칸이 없는 응답. 기본형이라 0으로 조용히 들어가면 0원 환불이 저장된다
        body.set("""
                {
                  "orderId": 10, "orderItemId": 101,
                  "buyerId": 1, "sellerId": 5,
                  "productOptionId": 55,
                  "productName": "감자", "optionName": "1kg",
                  "status": "PREPARING",
                  "quantity": 3
                }
                """);

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void 주문_서버가_꺼져_있으면_호출_실패로_바꾼다() {
        // given: 서버를 먼저 꺼서 연결이 거절되게 한다
        server.stop(0);

        // when & then
        assertThatThrownBy(() -> sut.getOrderItem(10L, 101L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
    }

    // 주문 ② 취소 반영. 요청 모양(경로·필드 이름)이 주문과의 약속이라, 어긋나면 주문 쪽에서 값이 비어 받으므로 실제 요청을 확인한다

    @Test
    void 취소_반영은_요청서_경로로_refundId와_수량과_뺀_배송비를_보낸다() {
        // given: 주문 API(#542)처럼 본문 없는 204로 답한다
        status.set(204);

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 2, 3_000L));

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.rejected()).isFalse();
            softly.assertThat(method.get()).isEqualTo("POST");
            softly.assertThat(path.get()).isEqualTo("/internal/v1/orders/items/101/refunds");
            softly.assertThat(requestBody.get()).isEqualToIgnoringWhitespace(
                    "{\"refundId\":7,\"quantity\":2,\"deductedShippingFee\":3000}");
        });
    }

    @Test
    void 취소_반영은_200도_성공으로_본다() {
        // given: 성공 코드가 정해지지 않아 2xx 전부를 성공으로 본다
        status.set(200);
        body.set("{}");

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));

        // then: 예외 없이 반영됨으로 끝난다
        assertThat(result.rejected()).isFalse();
        assertThat(path.get()).isEqualTo("/internal/v1/orders/items/101/refunds");
    }

    // 409만 거절 결과다. 404 같은 그 밖의 오류 상태는 일시적 실패와 같은 코드다.
    // 4xx 전체나 404를 반영됨·거절로 잘못 묶으면 주문이 모르는 취소의 돈이 나가거나 환불이 잘못 닫히므로 상태마다 확인한다
    @ParameterizedTest
    @MethodSource("_409가_아닌_오류_상태")
    void 취소_반영이_409가_아닌_오류_상태를_받으면_ORDER_API_FAILED(int errorStatus) {
        // given
        status.set(errorStatus);

        // when & then
        assertThatThrownBy(() -> sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
    }

    static Stream<Integer> _409가_아닌_오류_상태() {
        return Stream.of(400, 401, 403, 404, 500, 503);
    }

    // 요청은 받았는데 응답을 주지 않고 연결을 끊는 경우. 읽기 타임아웃(3초)과 같은 경로(응답 상태줄 읽기 실패)지만 기다리지 않으려고 연결을 닫는다
    @Test
    void 취소_반영_응답을_읽지_못하면_ORDER_API_FAILED() throws IOException {
        // given: 요청을 읽은 뒤 응답 없이 소켓을 끊는 서버. JDK HttpServer는 응답 없이 끊기가 어려워 소켓으로 직접 연다
        server.stop(0);
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("localhost"))) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket client = socket.accept()) {
                    client.getInputStream().read(new byte[4096]);
                } catch (IOException ignored) {
                    // 연결을 끊는 것이 목적이라 읽기 실패는 무시한다
                }
            });
            sut = new RefundOrderHttpAdapter("http://localhost:" + socket.getLocalPort());

            // when & then
            assertThatThrownBy(() -> sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L)))
                    .isInstanceOfSatisfying(RestApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
            acceptor.interrupt();
        }
    }

    @Test
    void 취소_반영이_409면_거절_결과와_주문의_설명을_돌려준다() {
        // given: 이유 이름(type)은 about:blank라 보지 않고 detail만 쓴다
        status.set(409);
        body.set("""
                { "type": "about:blank", "title": "Conflict", "status": 409, "detail": "이미 출고된 상품입니다." }""");

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.rejected()).isTrue();
            softly.assertThat(result.orderDetail()).isEqualTo("이미 출고된 상품입니다.");
        });
    }

    @Test
    void 취소_반영이_409인데_설명이_없으면_설명_없음으로_채운다() {
        // given: 본문이 비어 있다. 설명이 없다고 거절을 실패로 바꾸면 안 된다
        status.set(409);

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.rejected()).isTrue();
            softly.assertThat(result.orderDetail()).isEqualTo("주문 응답에 설명 없음");
        });
    }

    @Test
    void 취소_반영이_409인데_본문이_깨졌으면_설명_없음으로_채운다() {
        // given
        status.set(409);
        body.set("not json");

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.rejected()).isTrue();
            softly.assertThat(result.orderDetail()).isEqualTo("주문 응답에 설명 없음");
        });
    }
}
