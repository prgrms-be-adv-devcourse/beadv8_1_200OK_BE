package com.ok.refund.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 예시: 구매자가 감자(주문 상품 101) 3개와 배추(주문 상품 102)를 한 묶음으로 샀다. 주문은 환불 뒤에도 산 개수 3을 그대로 준다
class RefundQuantityPolicyTest {

    private static final Long POTATO = 101L;
    private static final Long CABBAGE = 102L;
    private static final long POTATO_ORDERED = 3;

    @Test
    void 감자_1개가_이미_취소됐으면_남은_2개는_요청할_수_있다() {
        // given
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(POTATO, 1, 0));

        // when
        boolean sut = RefundQuantityPolicy.isWithinRemaining(2, POTATO_ORDERED, POTATO, cancelled);

        // then
        assertThat(sut).isTrue();
    }

    @Test
    void 감자_1개가_이미_취소됐으면_3개는_남은_2개를_넘는다() {
        // given
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(POTATO, 1, 0));

        // when
        boolean sut = RefundQuantityPolicy.isWithinRemaining(3, POTATO_ORDERED, POTATO, cancelled);

        // then
        assertThat(sut).isFalse();
    }

    // 집계는 같은 묶음 전체를 주문 상품별로 준다. 다른 상품의 취소 개수를 감자에서 빼면 안 된다
    @Test
    void 배추만_취소됐으면_감자는_산_3개까지_요청할_수_있다() {
        // given
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(CABBAGE, 1, 0));

        // when
        boolean sut = RefundQuantityPolicy.isWithinRemaining(3, POTATO_ORDERED, POTATO, cancelled);

        // then
        assertThat(sut).isTrue();
    }

    @Test
    void 취소된_것이_없으면_산_개수보다_많이는_요청할_수_없다() {
        // given
        List<CancelledQuantity> cancelled = List.of();

        // when
        boolean sut = RefundQuantityPolicy.isWithinRemaining(4, POTATO_ORDERED, POTATO, cancelled);

        // then
        assertThat(sut).isFalse();
    }
}
