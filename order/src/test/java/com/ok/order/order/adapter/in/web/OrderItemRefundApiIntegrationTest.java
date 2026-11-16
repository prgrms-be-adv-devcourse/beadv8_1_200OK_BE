package com.ok.order.order.adapter.in.web;

import com.ok.order.OrderSchema;
import com.ok.order.order.application.dto.RecordOrderItemRefundCommand;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 환불 모듈이 승인 직후 부르는 환불 취소 반영 내부 API(요청서 ②)를 실제 DB 잠금까지 확인한다.
// 요청 본문 필드 이름은 환불 RefundOrderCancellationRequest와 같아야 한다(이름이 어긋나면 값이 비어 422가 된다).
class OrderItemRefundApiIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_028_001L;
    private static final Long SELLER_ID = 9_028_901L;
    private static final Long SOCK_OPTION_ID = 9_028_601L;
    private static final Long GLOVE_OPTION_ID = 9_028_602L;
    private static final String URL = "/internal/v1/orders/items/{orderItemId}/refunds";

    @Autowired
    WebApplicationContext context;

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Long orderId;
    private Long sockId;
    private Long gloveId;

    // 출고 전(배송 준비) 주문: 양말 10,000원 × 3개, 장갑 5,000원 × 1개 (같은 판매자 묶음)
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_028_501L, SOCK_OPTION_ID, SELLER_ID, "양말", "흰색", 10_000L, 3),
                new OrderLine(9_028_502L, GLOVE_OPTION_ID, SELLER_ID, "장갑", "검정", 5_000L, 1)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, 50_000L, 3_000L, 0L));
        order.assignIdempotencyKey("key-028-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);
        order.agreeThirdPartyProvision(true);
        orderId = orderService.create(order);
        orderService.completePayment(orderId);
        sockId = orderItemId(SOCK_OPTION_ID);
        gloveId = orderItemId(GLOVE_OPTION_ID);
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문을 지운다
    @AfterEach
    void tearDown() {
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        String items = "SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_item_refund WHERE order_item_id IN ("
                + items + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 출고_전_주문_상품에_환불을_반영하면_204이고_기록이_남으며_상태는_그대로다() throws Exception {
        // when
        recordRefund(sockId, 7L, 1, 3_000L).andExpect(status().isNoContent());

        // then
        assertSoftly(softly -> {
            softly.assertThat(refundRowCount(sockId)).isEqualTo(1);
            softly.assertThat(refundedQuantity(sockId)).isEqualTo(1);
            softly.assertThat(itemStatus(sockId)).isEqualTo("PREPARING");
        });
    }

    @Test
    void 같은_환불_ID로_같은_내용을_다시_보내면_204이고_한_번만_반영된다() throws Exception {
        // given
        recordRefund(sockId, 7L, 1, 3_000L).andExpect(status().isNoContent());

        // when
        recordRefund(sockId, 7L, 1, 3_000L).andExpect(status().isNoContent());

        // then
        assertThat(refundRowCount(sockId)).isEqualTo(1);
    }

    @Test
    void 같은_환불_ID로_다른_수량을_보내면_409다() throws Exception {
        // given
        recordRefund(sockId, 7L, 1, 0L).andExpect(status().isNoContent());

        // when & then
        recordRefund(sockId, 7L, 2, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.REFUND_ID_CONFLICT.slug())));
    }

    @Test
    void 같은_환불_ID를_다른_주문_상품에_보내면_409다() throws Exception {
        // given
        recordRefund(sockId, 7L, 1, 0L).andExpect(status().isNoContent());

        // when & then
        recordRefund(gloveId, 7L, 1, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.REFUND_ID_CONFLICT.slug())));
        assertThat(refundRowCount(gloveId)).isZero();
    }

    @Test
    void 환불_수량_합계가_주문_수량을_넘으면_409다() throws Exception {
        // given
        recordRefund(sockId, 7L, 2, 0L).andExpect(status().isNoContent());

        // when & then
        recordRefund(sockId, 8L, 2, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.REFUND_QUANTITY_EXCEEDED.slug())));
        assertThat(refundedQuantity(sockId)).isEqualTo(2);
    }

    @Test
    void 이미_출고된_주문_상품이면_409다() throws Exception {
        // given: 승인 뒤 판매자가 송장을 등록해 배송 중이 됐다
        orderService.startShipping(orderId, SELLER_ID);

        // when & then
        recordRefund(sockId, 7L, 1, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.REFUND_NOT_ALLOWED_STATUS.slug())));
        assertThat(refundRowCount(sockId)).isZero();
    }

    @Test
    void 없는_주문_상품이면_404다() throws Exception {
        recordRefund(9_028_999_999L, 7L, 1, 0L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.ORDER_ITEM_NOT_FOUND.slug())));
    }

    @Test
    void 환불_수량이_0이면_422다() throws Exception {
        recordRefund(sockId, 7L, 0, 0L).andExpect(status().isUnprocessableContent());
        assertThat(refundRowCount(sockId)).isZero();
    }

    @Test
    void 같은_주문_상품에_서로_다른_환불이_동시에_와도_합계가_주문_수량을_넘지_않는다() throws Exception {
        // given: 3개짜리 상품에 2개씩 두 환불이 동시에 온다. 하나만 반영돼야 한다
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> requests = List.of(7L, 8L).stream()
                .map(refundId -> (Callable<String>) () -> {
                    start.await();
                    try {
                        orderService.recordRefund(new RecordOrderItemRefundCommand(sockId, refundId, 2, 0L));
                        return "RECORDED";
                    } catch (RuntimeException e) {
                        return e.getClass().getSimpleName();
                    }
                })
                .toList();

        // when
        List<String> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = requests.stream().map(executor::submit).toList();
            start.countDown();
            results = futures.stream().map(OrderItemRefundApiIntegrationTest::await).toList();
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).containsExactlyInAnyOrder("RECORDED", "RestApiException");
            softly.assertThat(refundedQuantity(sockId)).isEqualTo(2);
        });
    }

    private ResultActions recordRefund(Long orderItemId, Long refundId, int quantity, long deductedShippingFee)
            throws Exception {
        return mockMvc.perform(post(URL, orderItemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "refundId": %d, "quantity": %d, "deductedShippingFee": %d }
                        """.formatted(refundId, quantity, deductedShippingFee)));
    }

    private Long orderItemId(Long productOptionId) {
        return jdbcTemplate.queryForObject("SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                        + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id"
                        + " WHERE g.order_id = ? AND oi.product_option_id = ?",
                Long.class, orderId, productOptionId);
    }

    private Integer refundRowCount(Long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME
                + ".order_item_refund WHERE order_item_id = ?", Integer.class, orderItemId);
    }

    private Integer refundedQuantity(Long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM " + OrderSchema.NAME
                + ".order_item_refund WHERE order_item_id = ?", Integer.class, orderItemId);
    }

    private String itemStatus(Long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT status FROM " + OrderSchema.NAME + ".order_item WHERE id = ?",
                String.class, orderItemId);
    }

    private static String await(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 요청이 끝나지 않았습니다.", e);
        }
    }
}
