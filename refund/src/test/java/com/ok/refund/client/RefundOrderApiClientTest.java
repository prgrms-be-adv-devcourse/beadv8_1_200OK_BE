package com.ok.refund.client;

import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.refund.service.dto.SellerGroupItem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 주문 응답은 요청서(#327)에 적은 필드 이름으로 약속했다. 이름이 어긋나면 컴파일은 되고 값만 0이나 null로 들어와
// 금액 계산이 조용히 틀리므로, 요청서 모양의 JSON이 그대로 옮겨 담기는지 JDK 내장 서버로 확인한다.
// 배송비·추가 운임·기본 배송비처럼 같은 타입 칸이 많아 서로 다른 값을 넣어 뒤바뀜도 잡는다
class RefundOrderApiClientTest {

    private final AtomicReference<String> body = new AtomicReference<>();
    private HttpServer server;
    private RefundOrderApiClient sut;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
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
        // given
        body.set("""
                {
                  "orderId": 10, "orderItemId": 101,
                  "buyerId": 1, "sellerId": 5,
                  "productOptionId": 55,
                  "productName": "양말", "optionName": "흰색",
                  "status": "PREPARING",
                  "unitPrice": 10000, "quantity": 3, "refundedQuantity": 1,
                  "sellerGroup": {
                    "shippingFee": 3000,
                    "remoteAreaFee": 2500,
                    "baseShippingFee": 3500,
                    "freeShippingThreshold": 50000,
                    "items": [
                      { "orderItemId": 101, "unitPrice": 10000, "quantity": 3 },
                      { "orderItemId": 102, "unitPrice": 5000, "quantity": 1 }
                    ]
                  }
                }
                """);

        // when
        RefundableOrderItem orderItem = sut.getOrderItem(10L, 101L);

        // then
        assertThat(orderItem).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderItem.unitPrice()).isEqualTo(10_000L);
            softly.assertThat(orderItem.quantity()).isEqualTo(3);
            softly.assertThat(orderItem.shippingFee()).isEqualTo(3_000L);
            softly.assertThat(orderItem.remoteAreaFee()).isEqualTo(2_500L);
            softly.assertThat(orderItem.baseShippingFee()).isEqualTo(3_500L);
            softly.assertThat(orderItem.freeShippingThreshold()).isEqualTo(50_000L);
            softly.assertThat(orderItem.sellerGroupItems()).containsExactly(
                    new SellerGroupItem(101L, 10_000L, 3), new SellerGroupItem(102L, 5_000L, 1));
        });
    }

    @Test
    void 무료배송_기준이_없으면_null로_받는다() {
        // given: 판매자가 무료배송 기준을 정하지 않은 주문. 0으로 바뀌면 "0원 이상 무료배송"이 되어 배송비를 잘못 빼게 된다
        body.set("""
                {
                  "orderId": 10, "orderItemId": 101, "buyerId": 1, "sellerId": 5, "productOptionId": 55,
                  "productName": "양말", "optionName": "흰색", "status": "PREPARING",
                  "unitPrice": 10000, "quantity": 3, "refundedQuantity": 0,
                  "sellerGroup": {
                    "shippingFee": 3000, "remoteAreaFee": 0, "baseShippingFee": 3000, "freeShippingThreshold": null,
                    "items": [ { "orderItemId": 101, "unitPrice": 10000, "quantity": 3 } ]
                  }
                }
                """);

        // when
        RefundableOrderItem orderItem = sut.getOrderItem(10L, 101L);

        // then
        assertThat(orderItem.freeShippingThreshold()).isNull();
    }
}
