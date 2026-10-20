package com.ok.order.order.domain;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.exception.OrderErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void 도서산간_운임은_판매자_묶음마다_붙고_총액에_더해진다() {
        // given: 판매자 2명 → 배송 2건
        Order sut = Order.create(1L, List.of(
                line(1L, 100L, 10_000L, 1),
                line(2L, 200L, 10_000L, 1)));

        // when
        sut.applyRemoteAreaFee(3_000L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getTotalRemoteAreaFee()).isEqualTo(6_000L);   // 묶음 2개 × 3,000
            softly.assertThat(sut.getTotalAmount()).isEqualTo(26_000L);
        });
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
}