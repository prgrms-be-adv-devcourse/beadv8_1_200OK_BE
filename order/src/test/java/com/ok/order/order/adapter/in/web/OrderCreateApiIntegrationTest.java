package com.ok.order.order.adapter.in.web;

import com.jayway.jsonpath.JsonPath;
import com.ok.common.event.OrderCreatedEvent;
import com.ok.order.OrderSchema;
import com.ok.order.cart.application.service.CartService;
import com.ok.order.order.application.dto.ProductOption;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeDeliveryPort;
import com.ok.testsupport.FakeProductPort;
import com.ok.testsupport.FakeWalletPort;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 생성 API를 보안 필터부터 DB 저장, 주문 생성 이벤트 발행까지 실제 경로로 확인한다.
// 상품·배송·지갑 HTTP 호출은 testsupport의 Fake가 대신한다. 결제 결과를 받은 뒤의 흐름은 OrderModuleTest가 확인한다.
// 헥사고날 구조 전환 전후에 같은 결과가 나오는지 보는 기준이 되도록 API 요청과 DB·이벤트 결과만 본다.
@RecordApplicationEvents
class OrderCreateApiIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_400_001L;
    private static final Long SELLER_ID = 9_400_901L;
    private static final Long PRODUCT_ID = 9_400_501L;
    private static final Long OPTION_ID = 9_400_601L;
    private static final long UNIT_PRICE = 12_000L;
    private static final int QUANTITY = 2;
    private static final long SHIPPING_FEE = 3_000L;
    private static final long REMOTE_AREA_FEE = 5_000L;
    // 상품 24,000 + 배송비 3,000 + 도서산간 5,000
    private static final long TOTAL_AMOUNT = UNIT_PRICE * QUANTITY + SHIPPING_FEE + REMOTE_AREA_FEE;
    private static final String ORDERS_URL = "/api/v1/orders";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationEvents events;

    @Autowired
    CartService cartService;

    @Autowired
    FakeProductPort fakeProductPort;

    @Autowired
    FakeDeliveryPort fakeDeliveryPort;

    @Autowired
    FakeWalletPort fakeWalletPort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cartService.addItem(BUYER_ID, OPTION_ID, QUANTITY);
        fakeProductPort.register(new ProductOption(
                PRODUCT_ID, OPTION_ID, SELLER_ID, "사과", "1kg", UNIT_PRICE, 10, true));
        fakeDeliveryPort.register(SELLER_ID, new SellerShippingFee(
                SHIPPING_FEE, null, SHIPPING_FEE, REMOTE_AREA_FEE));
        fakeWalletPort.setBalance(BUYER_ID, 100_000L);
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문·장바구니를 지운다
    @AfterEach
    void tearDown() {
        fakeProductPort.clear();
        fakeDeliveryPort.clear();
        fakeWalletPort.clear();
        deleteOrders();
        jdbcTemplate.update("DELETE ci FROM " + OrderSchema.NAME + ".cart_item ci JOIN " + OrderSchema.NAME
                + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", BUYER_ID);
    }

    @Test
    void 장바구니에_담은_상품으로_주문하면_결제_대기로_저장되고_주문_생성_이벤트가_나간다() throws Exception {
        // when
        Long orderId = orderId(createOrder("key-created").andExpect(status().isCreated()));

        // then
        Map<String, Object> order = jdbcTemplate.queryForMap("SELECT status, idempotency_key, total_product_amount, "
                + "total_shipping_fee, total_remote_area_fee, total_amount, recipient_name, zip_code, delivery_note FROM "
                + OrderSchema.NAME + ".orders WHERE id = ?", orderId);
        Map<String, Object> item = jdbcTemplate.queryForMap("SELECT oi.product_option_id, oi.quantity, oi.unit_price, "
                + "oi.status, g.seller_id, g.shipping_fee, g.remote_area_fee FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ?", orderId);
        List<OrderCreatedEvent> createdEvents = events.stream(OrderCreatedEvent.class).toList();

        assertThat(order).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(order.get("status")).isEqualTo("PAYMENT_PENDING");
            softly.assertThat(order.get("idempotency_key")).isEqualTo("key-created");
            softly.assertThat(((Number) order.get("total_product_amount")).longValue()).isEqualTo(UNIT_PRICE * QUANTITY);
            softly.assertThat(((Number) order.get("total_shipping_fee")).longValue()).isEqualTo(SHIPPING_FEE);
            softly.assertThat(((Number) order.get("total_remote_area_fee")).longValue()).isEqualTo(REMOTE_AREA_FEE);
            softly.assertThat(((Number) order.get("total_amount")).longValue()).isEqualTo(TOTAL_AMOUNT);
            softly.assertThat(order.get("recipient_name")).isEqualTo("홍길동");
            softly.assertThat(order.get("zip_code")).isEqualTo("63000");
            softly.assertThat(order.get("delivery_note")).isEqualTo("문 앞에 놓아 주세요");
            softly.assertThat(((Number) item.get("product_option_id")).longValue()).isEqualTo(OPTION_ID);
            softly.assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(QUANTITY);
            softly.assertThat(((Number) item.get("unit_price")).longValue()).isEqualTo(UNIT_PRICE);
            softly.assertThat(item.get("status")).isEqualTo("PAYMENT_PENDING");
            softly.assertThat(((Number) item.get("seller_id")).longValue()).isEqualTo(SELLER_ID);
            softly.assertThat(((Number) item.get("shipping_fee")).longValue()).isEqualTo(SHIPPING_FEE);
            softly.assertThat(((Number) item.get("remote_area_fee")).longValue()).isEqualTo(REMOTE_AREA_FEE);
            softly.assertThat(countConsents(orderId)).isEqualTo(1);
            softly.assertThat(fakeProductPort.allocatedOrderIds()).containsExactly(orderId);
            softly.assertThat(createdEvents).containsExactly(new OrderCreatedEvent(orderId, BUYER_ID, TOTAL_AMOUNT));
        });
    }

    @Test
    void 같은_멱등키로_두_번_요청하면_주문이_하나만_생긴다() throws Exception {
        // given
        Long firstOrderId = orderId(createOrder("key-retry").andExpect(status().isCreated()));

        // when
        Long secondOrderId = orderId(createOrder("key-retry").andExpect(status().isCreated()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(secondOrderId).isEqualTo(firstOrderId);
            softly.assertThat(countOrders()).isEqualTo(1);
            softly.assertThat(fakeProductPort.allocatedOrderIds()).containsExactly(firstOrderId);
            softly.assertThat(events.stream(OrderCreatedEvent.class).count()).isEqualTo(1);
        });
    }

    // 지금은 재고 선점에 실패하면 주문을 지우고 재고 해제를 시도한다. 주문을 취소 상태로 남기도록 바꾸면(#401) 이 테스트도 바꾼다
    @Test
    void 재고_선점이_실패하면_주문이_남지_않고_주문_생성_이벤트도_나가지_않는다() throws Exception {
        // given
        fakeProductPort.failAllocationWith(OrderErrorCode.OUT_OF_STOCK);

        // when
        createOrder("key-out-of-stock")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/" + OrderErrorCode.OUT_OF_STOCK.slug())));

        // then
        assertSoftly(softly -> {
            softly.assertThat(countOrders()).isZero();
            softly.assertThat(fakeProductPort.releasedOrderIds()).hasSize(1);
            softly.assertThat(events.stream(OrderCreatedEvent.class).count()).isZero();
        });
    }

    private ResultActions createOrder(String idempotencyKey) throws Exception {
        return mockMvc.perform(post(ORDERS_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "recipientName": "홍길동",
                          "recipientPhone": "010-1234-5678",
                          "zipCode": "63000",
                          "address": "제주특별자치도 제주시 첨단로 242",
                          "addressDetail": "101호",
                          "deliveryNote": "문 앞에 놓아 주세요",
                          "thirdPartyProvisionAgreed": true,
                          "expectedTotalAmount": %d
                        }
                        """.formatted(TOTAL_AMOUNT)));
    }

    private Long orderId(ResultActions result) throws Exception {
        Number orderId = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.orderId");
        return orderId.longValue();
    }

    private Integer countOrders() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?",
                Integer.class, BUYER_ID);
    }

    private Integer countConsents(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME
                + ".order_third_party_consent WHERE order_id = ?", Integer.class, orderId);
    }

    private void deleteOrders() {
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }
}
