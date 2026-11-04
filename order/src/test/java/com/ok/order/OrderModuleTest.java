package com.ok.order;

import com.ok.common.config.JpaConfig;
import com.ok.common.event.OrderPaidEvent;
import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;
import com.ok.order.cart.service.CartService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.order.order.repository.OrderRepository;
import com.ok.testsupport.FakeDeliveryApiClient;
import com.ok.testsupport.FakeProductApiClient;
import com.ok.testsupport.FakeWalletApiClient;
import com.ok.testsupport.TestClockConfiguration;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.assertj.core.groups.Tuple.tuple;

// 지갑이 보내는 결제 결과 이벤트를 받은 뒤 주문 모듈 안에서 이어지는 흐름을 확인한다.
// 주문 테스트에는 지갑 모듈이 없어 결제 결과 이벤트는 테스트가 지갑 대신 발행한다. 주문 생성 API는 OrderCreateApiIntegrationTest가 확인한다.
// 리스너는 커밋 뒤 다른 스레드에서 돌아서 Scenario로 다음 이벤트나 상태 변화가 나올 때까지 기다린다.
@ApplicationModuleTest
// 통합 테스트와 같이 test 프로필로 띄운다. 상품·배송·지갑 HTTP 호출은 Fake가 대신한다(모듈 테스트는 주문 패키지만 스캔해 직접 불러온다)
// 주문 서비스가 Clock을 주입받으므로 테스트용 Clock을 불러온다 (이유는 TestClockConfiguration 참고)
@ActiveProfiles("test")
@Import({JpaConfig.class, TestcontainersConfiguration.class, TestClockConfiguration.class,
        FakeProductApiClient.class, FakeDeliveryApiClient.class, FakeWalletApiClient.class})
class OrderModuleTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_400_002L;
    private static final Long SELLER_ID = 9_400_902L;
    private static final Long PRODUCT_ID = 9_400_502L;
    private static final Long ORDERED_OPTION_ID = 9_400_602L;
    // 장바구니에 같이 담겨 있지만 주문하지 않은 옵션. 결제 완료 뒤에도 장바구니에 남아야 한다
    private static final Long NOT_ORDERED_OPTION_ID = 9_400_603L;
    private static final long UNIT_PRICE = 12_000L;
    private static final int QUANTITY = 2;
    private static final long SHIPPING_FEE = 3_000L;
    // 상품 금액(24,000원)이 기준보다 작아 배송비를 낸다
    private static final long FREE_SHIPPING_THRESHOLD = 50_000L;
    private static final long TOTAL_AMOUNT = UNIT_PRICE * QUANTITY + SHIPPING_FEE;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 4, 10, 0);

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    CartService cartService;

    @Autowired
    FakeProductApiClient fakeProductApiClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CompletedEventPublications completedEventPublications;

    private Long orderId;

    @BeforeEach
    void setUp() {
        cartService.addItem(BUYER_ID, ORDERED_OPTION_ID, QUANTITY);
        cartService.addItem(BUYER_ID, NOT_ORDERED_OPTION_ID, 1);
        orderId = savePendingOrder();
    }

    // 모듈 테스트는 롤백되지 않아 이 클래스가 만든 주문·장바구니를 지운다
    @AfterEach
    void tearDown() {
        fakeProductApiClient.clear();
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
        jdbcTemplate.update("DELETE ci FROM " + OrderSchema.NAME + ".cart_item ci JOIN " + OrderSchema.NAME
                + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", BUYER_ID);
    }

    @Test
    void 결제_완료를_받으면_주문이_결제_완료되고_장바구니가_정리되고_배송용_이벤트가_나간다(Scenario scenario) {
        // when & then
        scenario.publish(new WalletPaymentCompletedEvent(orderId, BUYER_ID, TOTAL_AMOUNT, PAID_AT))
                .andWaitForEventOfType(OrderPaidEvent.class)
                .matching(event -> event.orderId().equals(orderId))
                .toArriveAndVerify(event -> {
                    assertThat(event).isNotNull();
                    assertSoftly(softly -> {
                        softly.assertThat(orderStatus()).isEqualTo("PAID");
                        softly.assertThat(itemStatuses()).containsOnly("PREPARING");
                        softly.assertThat(cartOptionIds()).containsExactly(NOT_ORDERED_OPTION_ID);
                        softly.assertThat(event.buyerId()).isEqualTo(BUYER_ID);
                        softly.assertThat(event.shippingAddress()).isEqualTo(new ShippingAddress(
                                "홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 152", "101호", "문 앞에 놓아 주세요"));
                        softly.assertThat(event.sellerOrders()).hasSize(1);
                        PaidSellerOrder sellerOrder = event.sellerOrders().getFirst();
                        softly.assertThat(sellerOrder.sellerId()).isEqualTo(SELLER_ID);
                        softly.assertThat(sellerOrder.baseShippingFee()).isEqualTo(SHIPPING_FEE);
                        softly.assertThat(sellerOrder.freeShippingThreshold()).isEqualTo(FREE_SHIPPING_THRESHOLD);
                        softly.assertThat(sellerOrder.regionSurcharge()).isZero();
                        softly.assertThat(sellerOrder.items()).extracting(PaidOrderItem::productName, PaidOrderItem::optionName,
                                        PaidOrderItem::quantity, PaidOrderItem::unitPrice)
                                .containsExactly(tuple("사과", "1kg", QUANTITY, UNIT_PRICE));
                    });
                });
    }

    @Test
    void 결제_실패를_받으면_주문이_결제_실패되고_재고를_풀고_장바구니는_그대로다(Scenario scenario) {
        // when & then
        scenario.publish(new WalletPaymentFailedEvent(orderId, BUYER_ID, WalletPaymentFailedEvent.INSUFFICIENT_BALANCE))
                .andWaitForStateChange(() -> fakeProductApiClient.releasedOrderIds().contains(orderId), released -> released)
                .andVerify(released -> assertSoftly(softly -> {
                    softly.assertThat(orderStatus()).isEqualTo("PAYMENT_FAILED");
                    softly.assertThat(itemStatuses()).containsOnly("PAYMENT_FAILED");
                    softly.assertThat(cartOptionIds()).containsExactlyInAnyOrder(ORDERED_OPTION_ID, NOT_ORDERED_OPTION_ID);
                }));
    }

    // Kafka로 옮기면 처음 켤 때 이미 처리한 메시지를 다시 받을 수 있어, 차례로 두 번 와도 배송이 한 번만 만들어지는지 본다.
    // 두 번이 동시에 오는 경우는 이 테스트 범위 밖이다 (결제 결과 리스너 동시 처리 방지는 별도 이슈)
    @Test
    void 같은_결제_완료를_차례로_두_번_받아도_배송용_이벤트는_한_번만_나간다(Scenario scenario, AssertablePublishedEvents events) {
        // given
        WalletPaymentCompletedEvent completed = new WalletPaymentCompletedEvent(orderId, BUYER_ID, TOTAL_AMOUNT, PAID_AT);
        scenario.publish(completed)
                .andWaitForEventOfType(OrderPaidEvent.class)
                .matching(event -> event.orderId().equals(orderId))
                .toArrive();

        // when: 두 번째 이벤트의 처리가 끝날 때까지 기다린다
        scenario.publish(completed)
                .andWaitForStateChange(this::countCompletedPaymentEvents, count -> count == 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(events.ofType(OrderPaidEvent.class)
                    .matching(event -> event.orderId().equals(orderId))).hasSize(1);
            softly.assertThat(orderStatus()).isEqualTo("PAID");
        });
    }

    // 주문 생성 API를 거치지 않고 결제 대기 주문을 바로 저장한다. 주문 생성은 통합 테스트가 확인한다
    private Long savePendingOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(PRODUCT_ID, ORDERED_OPTION_ID, SELLER_ID, "사과", "1kg", UNIT_PRICE, QUANTITY)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(SHIPPING_FEE, FREE_SHIPPING_THRESHOLD, SHIPPING_FEE, 0L));
        order.assignIdempotencyKey("module-test-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 152", "101호",
                "문 앞에 놓아 주세요");
        order.agreeThirdPartyProvision(true);
        return orderRepository.save(order).getId();
    }

    private String orderStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM " + OrderSchema.NAME + ".orders WHERE id = ?",
                String.class, orderId);
    }

    private List<String> itemStatuses() {
        return jdbcTemplate.queryForList("SELECT oi.status FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ?",
                String.class, orderId);
    }

    private List<Long> cartOptionIds() {
        return jdbcTemplate.queryForList("SELECT ci.product_option_id FROM " + OrderSchema.NAME + ".cart_item ci JOIN "
                + OrderSchema.NAME + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", Long.class, BUYER_ID);
    }

    // 리스너 처리가 끝난 결제 완료 이벤트 수. 완료 기록을 지우도록 설정이 바뀌면 기다리는 방식을 바꿔야 한다
    private long countCompletedPaymentEvents() {
        return completedEventPublications.findAll().stream()
                .map(EventPublication::getEvent)
                .filter(e -> e instanceof WalletPaymentCompletedEvent completed && completed.orderId().equals(orderId))
                .count();
    }
}
