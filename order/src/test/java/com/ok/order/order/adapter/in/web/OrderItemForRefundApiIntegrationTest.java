package com.ok.order.order.adapter.in.web;

import com.ok.order.OrderSchema;
import com.ok.order.order.application.service.OrderService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 환불 모듈이 부르는 환불용 주문 상품 조회 내부 API를 확인한다. MockMvc 요청은 127.0.0.1에서 오므로 loopback 검사를 통과한다.
// 응답 필드 이름은 환불 RefundOrderItemResponse가 받는 형식과 같아야 한다(이름을 바꾸면 환불이 오류 없이 값을 잃는다).
class OrderItemForRefundApiIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_449_001L;
    private static final Long SELLER_ID = 9_449_901L;
    private static final Long NO_FREE_SHIPPING_SELLER_ID = 9_449_902L;
    private static final String URL = "/internal/v1/orders/{orderId}/items/{orderItemId}";

    @Autowired
    WebApplicationContext context;

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문을 지운다
    @AfterEach
    void tearDown() {
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 주문_상품과_판매자_묶음의_배송비_값과_같은_묶음의_모든_주문_상품을_응답한다() throws Exception {
        // given: 판매자 A 묶음에 양말·장갑, 판매자 B 묶음에 모자
        Long orderId = saveOrder(List.of(
                new OrderLine(9_449_501L, 9_449_601L, SELLER_ID, "양말", "흰색", 10_000L, 3),
                new OrderLine(9_449_502L, 9_449_602L, SELLER_ID, "장갑", "검정", 5_000L, 1),
                new OrderLine(9_449_503L, 9_449_603L, NO_FREE_SHIPPING_SELLER_ID, "모자", "회색", 20_000L, 1)));
        Long sockId = orderItemId(orderId, 9_449_601L);
        Long gloveId = orderItemId(orderId, 9_449_602L);

        // when & then
        mockMvc.perform(get(URL, orderId, sockId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.orderItemId").value(sockId))
                .andExpect(jsonPath("$.buyerId").value(BUYER_ID))
                .andExpect(jsonPath("$.sellerId").value(SELLER_ID))
                .andExpect(jsonPath("$.productOptionId").value(9_449_601L))
                .andExpect(jsonPath("$.productName").value("양말"))
                .andExpect(jsonPath("$.optionName").value("흰색"))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.unitPrice").value(10_000))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.sellerGroup.shippingFee").value(3_000))
                .andExpect(jsonPath("$.sellerGroup.remoteAreaFee").value(5_000))
                .andExpect(jsonPath("$.sellerGroup.baseShippingFee").value(3_000))
                .andExpect(jsonPath("$.sellerGroup.freeShippingThreshold").value(50_000))
                .andExpect(jsonPath("$.sellerGroup.items.length()").value(2))
                .andExpect(jsonPath("$.sellerGroup.items[?(@.orderItemId == " + sockId + ")].unitPrice").value(10_000))
                .andExpect(jsonPath("$.sellerGroup.items[?(@.orderItemId == " + sockId + ")].quantity").value(3))
                .andExpect(jsonPath("$.sellerGroup.items[?(@.orderItemId == " + gloveId + ")].unitPrice").value(5_000))
                .andExpect(jsonPath("$.sellerGroup.items[?(@.orderItemId == " + gloveId + ")].quantity").value(1));
    }

    @Test
    void 무료배송_기준이_없는_판매자면_무료배송_기준을_null로_응답한다() throws Exception {
        // given
        Long orderId = saveOrder(List.of(
                new OrderLine(9_449_503L, 9_449_603L, NO_FREE_SHIPPING_SELLER_ID, "모자", "회색", 20_000L, 1)));
        Long hatId = orderItemId(orderId, 9_449_603L);

        // when & then
        mockMvc.perform(get(URL, orderId, hatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerGroup.baseShippingFee").value(2_500))
                .andExpect(jsonPath("$.sellerGroup.freeShippingThreshold", nullValue()));
    }

    @Test
    void 주문이_없으면_404를_응답한다() throws Exception {
        mockMvc.perform(get(URL, 9_449_999_999L, 1L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.ORDER_NOT_FOUND.slug())));
    }

    @Test
    void 다른_주문의_주문_상품이면_404를_응답한다() throws Exception {
        // given: 두 주문 중 다른 주문의 주문 상품 ID로 조회
        Long orderId = saveOrder(List.of(
                new OrderLine(9_449_501L, 9_449_601L, SELLER_ID, "양말", "흰색", 10_000L, 1)));
        Long otherOrderId = saveOrder(List.of(
                new OrderLine(9_449_502L, 9_449_602L, SELLER_ID, "장갑", "검정", 5_000L, 1)));
        Long otherItemId = orderItemId(otherOrderId, 9_449_602L);

        // when & then
        mockMvc.perform(get(URL, orderId, otherItemId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.ORDER_ITEM_NOT_FOUND.slug())));
    }

    @Test
    void 조회해도_주문_상품_상태는_바뀌지_않는다() throws Exception {
        // given
        Long orderId = saveOrder(List.of(
                new OrderLine(9_449_501L, 9_449_601L, SELLER_ID, "양말", "흰색", 10_000L, 1)));
        Long sockId = orderItemId(orderId, 9_449_601L);
        Map<String, Object> before = itemRow(sockId);

        // when
        mockMvc.perform(get(URL, orderId, sockId)).andExpect(status().isOk());

        // then
        assertThat(itemRow(sockId)).isEqualTo(before);
    }

    private Long saveOrder(List<OrderLine> lines) {
        Order order = Order.create(BUYER_ID, lines);
        if (lines.stream().anyMatch(line -> line.sellerId().equals(SELLER_ID))) {
            order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, 50_000L, 3_000L, 5_000L));
        }
        if (lines.stream().anyMatch(line -> line.sellerId().equals(NO_FREE_SHIPPING_SELLER_ID))) {
            order.applyShippingFee(NO_FREE_SHIPPING_SELLER_ID, new SellerShippingFee(2_500L, null, 2_500L, 0L));
        }
        order.assignIdempotencyKey("key-449-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "63000", "제주특별자치도 제주시 첨단로 242", "101호", null);
        order.agreeThirdPartyProvision(true);
        return orderService.create(order);
    }

    private Long orderItemId(Long orderId, Long productOptionId) {
        return jdbcTemplate.queryForObject("SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                        + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id"
                        + " WHERE g.order_id = ? AND oi.product_option_id = ?",
                Long.class, orderId, productOptionId);
    }

    private Map<String, Object> itemRow(Long orderItemId) {
        return jdbcTemplate.queryForMap("SELECT status, updated_at FROM " + OrderSchema.NAME
                + ".order_item WHERE id = ?", orderItemId);
    }
}
