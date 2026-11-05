package com.ok.refund.client;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 주문 응답은 요청서에 적은 필드 이름으로 약속했다. 이름이 어긋나면 컴파일은 되고 값만 0이나 null로 들어와
// 조용히 틀리므로, 요청서 모양의 JSON이 그대로 옮겨 담기는지 JDK 내장 서버로 확인한다.
// 주문 팀 API가 아직 없어 실제 주문 모듈 대신 이 서버가 요청서대로 응답한다
class RefundOrderApiClientTest {

    private static final String ITEMS_JSON = """
            [ { "orderItemId": 101, "unitPrice": 10000, "quantity": 3 },
                { "orderItemId": 102, "unitPrice": 5000, "quantity": 1 } ]""";

    // 요청서 ① 응답 전체 모양. 테스트마다 칸 하나만 바꿔 쓴다
    private static final String VALID_BODY = """
            {
              "orderId": 10, "orderItemId": 101,
              "buyerId": 1, "sellerId": 5,
              "productOptionId": 55,
              "productName": "양말", "optionName": "흰색",
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
    private HttpServer server;
    private RefundOrderApiClient sut;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
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
        sut = new RefundOrderApiClient("http://localhost:" + server.getAddress().getPort());
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
            softly.assertThat(orderItem.productName()).isEqualTo("양말");
            softly.assertThat(orderItem.optionName()).isEqualTo("흰색");
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
        BundleShipping sut = this.sut.getOrderItem(10L, 101L).bundle();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.shippingFee()).isEqualTo(1_000L);
            softly.assertThat(sut.remoteAreaFee()).isEqualTo(2_000L);
            softly.assertThat(sut.baseShippingFee()).isEqualTo(3_000L);
            softly.assertThat(sut.freeShippingThreshold()).isEqualTo(50_000L);
            softly.assertThat(sut.items()).containsExactly(
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
                  "productName": "양말", "optionName": "흰색",
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
}
