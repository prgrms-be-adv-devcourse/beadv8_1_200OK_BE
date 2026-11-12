package com.ok.order.order.domain;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.exception.OrderErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class OrderTest {

    // 테스트용 주문서 한 줄: 옵션 ID, 판매자 ID, 가격, 수량만 바꿔 가며 사용
    private OrderLine line(Long optionId, Long sellerId, long unitPrice, int quantity) {
        return new OrderLine(optionId, optionId, sellerId, "상품" + optionId, "옵션", unitPrice, quantity);
    }

    @Test
    void 같은_판매자_상품은_한_묶음이_되고_상품_금액을_합산한다() {
        // given: 판매자 100의 상품 2개, 판매자 200의 상품 1개
        List<OrderLine> lines = List.of(
                line(1L, 100L, 10_000L, 2),   // 20,000
                line(2L, 100L, 5_000L, 1),    //  5,000
                line(3L, 200L, 3_000L, 1));   //  3,000

        // when
        Order sut = Order.create(1L, lines);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSellerGroups()).hasSize(2);
            softly.assertThat(sut.getSellerGroups().get(0).getItems()).hasSize(2);
            softly.assertThat(sut.getTotalProductAmount()).isEqualTo(28_000L);
            softly.assertThat(sut.getTotalAmount()).isEqualTo(28_000L);   // 배송비·운임 0원
        });
    }

    @Test
    void 주문과_주문_상품은_결제_대기_상태로_시작한다() {
        // given
        List<OrderLine> lines = List.of(line(1L, 100L, 10_000L, 1));

        // when
        Order sut = Order.create(1L, lines);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            softly.assertThat(sut.getAllItems())
                    .allMatch(item -> item.getStatus() == OrderItemStatus.PAYMENT_PENDING);
        });
    }

    @Test
    void 주문할_상품이_없으면_주문을_만들_수_없다() {
        // given
        List<OrderLine> lines = List.of();

        // when & then
        assertThatThrownBy(() -> Order.create(1L, lines))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.EMPTY_ORDER_ITEMS));
    }

    @Test
    void 배송비와_도서산간_운임은_판매자_묶음마다_붙고_총액에_더해진다() {
        // given: 판매자 2명 → 배송 2건. 판매자 100은 배송비 3,000원, 판매자 200은 무료배송
        Order sut = Order.create(1L, List.of(
                line(1L, 100L, 10_000L, 1),
                line(2L, 200L, 10_000L, 1)));

        // when: 두 묶음 모두 도서산간 운임 3,000원 (무료배송이어도 붙는다)
        sut.applyShippingFee(100L, new SellerShippingFee(3_000L, 50_000L, 3_000L, 3_000L));
        sut.applyShippingFee(200L, new SellerShippingFee(3_000L, 0L, 0L, 3_000L));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getTotalShippingFee()).isEqualTo(3_000L);
            softly.assertThat(sut.getTotalRemoteAreaFee()).isEqualTo(6_000L);   // 묶음 2개 × 3,000
            softly.assertThat(sut.getTotalAmount()).isEqualTo(29_000L);
        });
    }

    @Test
    void 주문에_없는_판매자의_배송비는_적용할_수_없다() {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));

        // when & then
        assertThatThrownBy(() -> sut.applyShippingFee(999L, new SellerShippingFee(3_000L, null, 3_000L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정보_제공_동의는_판매자마다_한_줄씩_남는다() {
        // given: 판매자 100의 상품 2개, 판매자 200의 상품 1개
        Order sut = Order.create(1L, List.of(
                line(1L, 100L, 10_000L, 1),
                line(2L, 100L, 10_000L, 1),
                line(3L, 200L, 10_000L, 1)));

        // when
        sut.agreeThirdPartyProvision(true);

        // then
        assertThat(sut.getThirdPartyConsents())
                .extracting(OrderThirdPartyConsent::getSellerId)
                .containsExactly(100L, 200L);
    }

    @Test
    void 정보_제공에_동의하지_않으면_주문할_수_없다() {
        // given
        Order order = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));

        // when & then
        assertThatThrownBy(() -> order.agreeThirdPartyProvision(false))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.THIRD_PARTY_CONSENT_REQUIRED));
    }

    @Test
    void 결제가_완료되면_주문은_결제_완료_상품은_배송_준비가_된다() {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1), line(2L, 200L, 5_000L, 1)));

        // when
        sut.markPaid();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(OrderStatus.PAID);
            softly.assertThat(sut.getAllItems())
                    .extracting(OrderItem::getStatus)
                    .containsOnly(OrderItemStatus.PREPARING);
        });
    }

    @Test
    void 취소하면_주문과_상품_모두_취소되고_멱등키를_비운다() {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1), line(2L, 200L, 5_000L, 2)));
        sut.assignIdempotencyKey("key-1");

        // when
        boolean canceled = sut.cancel();

        // then
        assertSoftly(softly -> {
            softly.assertThat(canceled).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(OrderStatus.CANCELED);
            softly.assertThat(sut.getIdempotencyKey()).isNull();
            softly.assertThat(sut.getAllItems())
                    .extracting(OrderItem::getStatus)
                    .containsOnly(OrderItemStatus.CANCELED);
        });
    }

    @ParameterizedTest
    @MethodSource("결제_결과를_받은_주문")
    void 결제_결과를_받은_주문은_취소해도_바뀌지_않는다(Consumer<Order> paymentResult, OrderStatus status, OrderItemStatus itemStatus) {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        sut.assignIdempotencyKey("key-1");
        paymentResult.accept(sut);

        // when
        boolean canceled = sut.cancel();

        // then
        assertSoftly(softly -> {
            softly.assertThat(canceled).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(status);
            softly.assertThat(sut.getIdempotencyKey()).isEqualTo("key-1");
            softly.assertThat(sut.getAllItems())
                    .extracting(OrderItem::getStatus)
                    .containsOnly(itemStatus);
        });
    }

    static Stream<Arguments> 결제_결과를_받은_주문() {
        return Stream.of(
                Arguments.of((Consumer<Order>) Order::markPaid, OrderStatus.PAID, OrderItemStatus.PREPARING),
                Arguments.of((Consumer<Order>) Order::markPaymentFailed, OrderStatus.PAYMENT_FAILED, OrderItemStatus.PAYMENT_FAILED)
        );
    }

    @Test
    void 결제가_실패하면_주문과_상품_모두_결제_실패가_된다() {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));

        // when
        sut.markPaymentFailed();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            softly.assertThat(sut.getAllItems())
                    .extracting(OrderItem::getStatus)
                    .containsOnly(OrderItemStatus.PAYMENT_FAILED);
        });
    }

    // ---------- 배송 상태 반영 ----------

    @Test
    void 결제_완료된_상품은_배송이_시작되면_배송_중이_된다() {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        sut.markPaid();

        // when
        int changed = sut.markShipping(100L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isEqualTo(1);
            softly.assertThat(sut.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.SHIPPING);
        });
    }

    @Test
    void 배송_시작은_해당_판매자의_상품만_바꾼다() {
        // given: 판매자 100, 200
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1), line(2L, 200L, 5_000L, 1)));
        sut.markPaid();

        // when
        sut.markShipping(100L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getSellerGroups().get(0).getItems().get(0).getStatus()).isEqualTo(OrderItemStatus.SHIPPING);
            softly.assertThat(sut.getSellerGroups().get(1).getItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PREPARING);
        });
    }

    @Test
    void 결제_완료_전_상품은_배송이_시작돼도_바뀌지_않는다() {
        // given: 결제 대기
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));

        // when
        int changed = sut.markShipping(100L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isZero();
            softly.assertThat(sut.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PAYMENT_PENDING);
        });
    }

    @Test
    void 결제_실패한_상품은_배송이_완료돼도_바뀌지_않는다() {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        sut.markPaymentFailed();

        // when
        int changed = sut.markDelivered(100L, LocalDateTime.of(2026, 10, 20, 15, 0));

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isZero();
            softly.assertThat(sut.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PAYMENT_FAILED);
            softly.assertThat(sut.getAllItems().get(0).getDeliveredAt()).isNull();
        });
    }

    @Test
    void 배송이_완료되면_배송_완료가_되고_배송_완료_시각이_저장된다() {
        // given
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 20, 15, 0);
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        sut.markPaid();
        sut.markShipping(100L);

        // when
        sut.markDelivered(100L, deliveredAt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.DELIVERED);
            softly.assertThat(sut.getAllItems().get(0).getDeliveredAt()).isEqualTo(deliveredAt);
        });
    }

    @Test
    void 배송_시작보다_완료가_먼저_와도_배송_완료가_되고_늦게_온_시작은_무시된다() {
        // given: 배송 준비
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        sut.markPaid();

        // when
        sut.markDelivered(100L, LocalDateTime.of(2026, 10, 20, 15, 0));
        int lateShipping = sut.markShipping(100L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(lateShipping).isZero();
            softly.assertThat(sut.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.DELIVERED);
        });
    }

    @Test
    void 배송_완료가_두_번_와도_처음_시각이_유지된다() {
        // given
        LocalDateTime first = LocalDateTime.of(2026, 10, 20, 15, 0);
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        sut.markPaid();
        sut.markDelivered(100L, first);

        // when
        int changed = sut.markDelivered(100L, LocalDateTime.of(2026, 10, 21, 9, 0));

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isZero();
            softly.assertThat(sut.getAllItems().get(0).getDeliveredAt()).isEqualTo(first);
        });
    }

    @Test
    void 주문에_없는_판매자의_배송_이벤트는_아무것도_바꾸지_않는다() {
        // given
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        sut.markPaid();

        // when
        int changed = sut.markShipping(999L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isZero();
            softly.assertThat(sut.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PREPARING);
        });
    }


    // ---------- 구매확정 ----------

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 9, 0);

    // 결제 → 배송 완료까지 끝난 주문
    private Order deliveredOrder(LocalDateTime deliveredAt) {
        Order order = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        order.markPaid();
        order.markDelivered(100L, deliveredAt);
        return order;
    }

    @Test
    void 배송_완료된_상품은_구매확정되고_확정_시각이_저장된다() {
        OrderItem sut = deliveredOrder(NOW.minusDays(1)).getAllItems().get(0);

        boolean confirmed = sut.confirmPurchase(NOW);

        assertSoftly(softly -> {
            softly.assertThat(confirmed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(OrderItemStatus.PURCHASE_CONFIRMED);
            softly.assertThat(sut.getConfirmedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 배송_완료_전_상품은_구매확정되지_않는다() {
        Order order = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1)));
        order.markPaid();
        OrderItem sut = order.getAllItems().get(0);

        boolean confirmed = sut.confirmPurchase(NOW);

        assertSoftly(softly -> {
            softly.assertThat(confirmed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(OrderItemStatus.PREPARING);
            softly.assertThat(sut.getConfirmedAt()).isNull();
        });
    }

    @Test
    void 환불_중인_상품은_구매확정되지_않는다() {
        // 환불 요청 기능(#28)이 아직 없어서 상태를 직접 바꿈
        OrderItem sut = deliveredOrder(NOW.minusDays(1)).getAllItems().get(0);
        ReflectionTestUtils.setField(sut, "status", OrderItemStatus.CANCEL_REQUESTED);

        boolean confirmed = sut.confirmPurchase(NOW);

        assertSoftly(softly -> {
            softly.assertThat(confirmed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(OrderItemStatus.CANCEL_REQUESTED);
        });
    }

    @Test
    void 이미_구매확정된_상품은_다시_확정되지_않고_처음_시각이_유지된다() {
        OrderItem sut = deliveredOrder(NOW.minusDays(1)).getAllItems().get(0);
        sut.confirmPurchase(NOW);

        boolean confirmedAgain = sut.confirmPurchase(NOW.plusDays(1));

        assertSoftly(softly -> {
            softly.assertThat(confirmedAgain).isFalse();
            softly.assertThat(sut.getConfirmedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 자동_구매확정은_기준_시각_이전에_배송_완료된_상품만_확정한다() {
        // 판매자 100은 4일 전, 판매자 200은 1일 전 배송 완료
        Order sut = Order.create(1L, List.of(line(1L, 100L, 10_000L, 1), line(2L, 200L, 5_000L, 1)));
        sut.markPaid();
        sut.markDelivered(100L, NOW.minusDays(4));
        sut.markDelivered(200L, NOW.minusDays(1));

        List<OrderItem> confirmed = sut.confirmPurchasesDeliveredBefore(NOW.minusDays(3), NOW);

        assertSoftly(softly -> {
            softly.assertThat(confirmed).hasSize(1);
            softly.assertThat(sut.getSellerGroups().get(0).getItems().get(0).getStatus())
                    .isEqualTo(OrderItemStatus.PURCHASE_CONFIRMED);
            softly.assertThat(sut.getSellerGroups().get(1).getItems().get(0).getStatus())
                    .isEqualTo(OrderItemStatus.DELIVERED);
        });
    }

    @Test
    void 배송_완료가_정확히_기준_시각이면_자동_구매확정된다() {
        Order sut = deliveredOrder(NOW.minusDays(3));

        List<OrderItem> confirmed = sut.confirmPurchasesDeliveredBefore(NOW.minusDays(3), NOW);

        assertThat(confirmed).hasSize(1);
    }

    @Test
    void 주문에_없는_상품_ID로_찾으면_비어_있다() {
        Order sut = deliveredOrder(NOW.minusDays(1));

        assertThat(sut.findItem(999L)).isEmpty();
    }

    // ---------- 환불 취소 반영 (#28) ----------

    // 결제 완료로 출고 전(배송 준비)이 된 주문 상품 (판매자 100, 10,000원 × 3개)
    private OrderItem preparingItem() {
        Order order = Order.create(1L, List.of(line(1L, 100L, 10_000L, 3)));
        order.markPaid();
        return order.getAllItems().get(0);
    }

    @Test
    void 출고_전_상품에_환불을_기록하면_환불_수량만큼_정산할_상품_금액이_줄고_상태는_그대로다() {
        // given
        OrderItem sut = preparingItem();

        // when
        boolean recorded = sut.recordRefund(7L, 1, 0L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(recorded).isTrue();
            softly.assertThat(sut.refundedQuantity()).isEqualTo(1);
            softly.assertThat(sut.confirmedItemAmount()).isEqualTo(20_000L);
            softly.assertThat(sut.isFullyRefunded()).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(OrderItemStatus.PREPARING);
        });
    }

    @Test
    void 같은_환불_ID가_같은_내용으로_다시_오면_기록하지_않는다() {
        // given
        OrderItem sut = preparingItem();
        sut.recordRefund(7L, 1, 3_000L);

        // when
        boolean recorded = sut.recordRefund(7L, 1, 3_000L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(recorded).isFalse();
            softly.assertThat(sut.refundedQuantity()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @MethodSource("같은_환불_ID의_다른_내용")
    void 같은_환불_ID가_다른_내용으로_오면_거절한다(int quantity, long deductedShippingFee) {
        // given
        OrderItem sut = preparingItem();
        sut.recordRefund(7L, 1, 3_000L);

        // when & then
        assertThatThrownBy(() -> sut.recordRefund(7L, quantity, deductedShippingFee))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.REFUND_ID_CONFLICT));
    }

    static Stream<Arguments> 같은_환불_ID의_다른_내용() {
        return Stream.of(
                Arguments.of(2, 3_000L),   // 수량이 다름
                Arguments.of(1, 0L));      // 뺀 배송비가 다름
    }

    @ParameterizedTest
    @MethodSource("출고_전이_아닌_주문")
    void 출고_전이_아니면_환불을_기록할_수_없다(Consumer<Order> progress) {
        // given
        Order order = Order.create(1L, List.of(line(1L, 100L, 10_000L, 3)));
        progress.accept(order);
        OrderItem sut = order.getAllItems().get(0);

        // when & then
        assertThatThrownBy(() -> sut.recordRefund(7L, 1, 0L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.REFUND_NOT_ALLOWED_STATUS));
        assertThat(sut.refundedQuantity()).isZero();
    }

    static Stream<Consumer<Order>> 출고_전이_아닌_주문() {
        return Stream.of(
                order -> { },                                   // 결제 대기
                order -> {                                      // 배송 중
                    order.markPaid();
                    order.markShipping(100L);
                },
                order -> {                                      // 배송 완료
                    order.markPaid();
                    order.markDelivered(100L, NOW);
                });
    }

    @Test
    void 환불_수량_합계가_주문_수량을_넘으면_거절한다() {
        // given
        OrderItem sut = preparingItem();
        sut.recordRefund(7L, 2, 0L);

        // when & then
        assertThatThrownBy(() -> sut.recordRefund(8L, 2, 0L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.REFUND_QUANTITY_EXCEEDED));
        assertThat(sut.refundedQuantity()).isEqualTo(2);
    }

    @Test
    void 모두_환불하면_정산할_상품_금액이_없다() {
        // given
        OrderItem sut = preparingItem();

        // when
        sut.recordRefund(7L, 1, 0L);
        sut.recordRefund(8L, 2, 0L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.isFullyRefunded()).isTrue();
            softly.assertThat(sut.confirmedItemAmount()).isZero();
        });
    }

    @Test
    void 판매자_몫_배송비는_묶음_배송비에_묶음_안_환불에서_뺀_배송비를_더한다() {
        // given: 무료배송 묶음(구매자 배송비 0원)에서 일부 환불로 기준 아래가 되어 환불이 기본 배송비 3,000원을 뺐다
        Order order = Order.create(1L, List.of(line(1L, 100L, 30_000L, 1), line(2L, 100L, 20_000L, 1)));
        order.applyShippingFee(100L, new SellerShippingFee(3_000L, 50_000L, 0L, 0L));
        order.markPaid();
        OrderItem refunded = order.getAllItems().get(0);
        refunded.recordRefund(7L, 1, 3_000L);

        // when
        long sut = refunded.getSellerGroup().shippingFeeForPayout();

        // then
        assertThat(sut).isEqualTo(3_000L);
    }

    @Test
    void 구매자가_배송비를_냈고_뺀_배송비가_없으면_판매자_몫_배송비는_묶음_배송비_그대로다() {
        // given
        Order order = Order.create(1L, List.of(line(1L, 100L, 10_000L, 2)));
        order.applyShippingFee(100L, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.markPaid();
        OrderItem item = order.getAllItems().get(0);
        item.recordRefund(7L, 1, 0L);

        // when
        long sut = item.getSellerGroup().shippingFeeForPayout();

        // then
        assertThat(sut).isEqualTo(3_000L);
    }
}