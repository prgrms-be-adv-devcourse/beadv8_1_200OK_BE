package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutCandidateItemTest {

    // 주문 ID와 주문항목 ID는 일부러 다른 값. 같은 값이면 두 필드의 자리가 바뀌어도 티가 안 남
    private static final Long ORDER_ID = 100L;
    private static final Long ORDER_ITEM_ID = 1L;
    private static final Long SELLER_ID = 10L;

    @Test
    void 판매_수수료는_시스템_몫이다() {
        // given
        PayoutCandidateItem sut = PayoutCandidateItem.ofSaleFee(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 300L);

        // when & then
        assertThat(sut.isSystemShare()).isTrue();
    }

    @Test
    void 판매_대금과_배송비는_판매자_몫이다() {
        // given
        PayoutCandidateItem amount = PayoutCandidateItem.ofSaleAmount(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 9_700L);
        PayoutCandidateItem shippingFee = PayoutCandidateItem.ofSaleShippingFee(ORDER_ID, SELLER_ID, 3_000L);

        // when & then
        assertThat(amount.isSystemShare()).isFalse();
        assertThat(shippingFee.isSystemShare()).isFalse();
    }

    @Test
    void 배송비_후보는_주문상품_ID_없이_주문과_판매자로_만들어진다() {
        // given & when
        PayoutCandidateItem sut = PayoutCandidateItem.ofSaleShippingFee(ORDER_ID, SELLER_ID, 3_000L);

        // then
        // 배송비는 상품이 아니라 판매자 묶음 배송 1건의 금액이라 주문상품 ID가 없음 (DB CHECK와 같은 규칙)
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getEventType()).isEqualTo(PayoutEventType.SALE_SHIPPING_FEE);
            softly.assertThat(sut.getOrderId()).isEqualTo(ORDER_ID);
            softly.assertThat(sut.getOrderItemId()).isNull();
            softly.assertThat(sut.getSellerId()).isEqualTo(SELLER_ID);
            softly.assertThat(sut.getAmount()).isEqualTo(3_000L);
        });
    }

    @Test
    void 조정_번호가_음수면_예외가_난다() {
        // when & then
        assertThatThrownBy(() -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -500L, -1L, 99L, "금액 오입력 차감"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.INVALID_ADJUSTMENT_ID));
    }

    @Test
    void 수동_조정에_관리자_ID가_없으면_예외가_난다() {
        // when & then
        assertThatThrownBy(() -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -500L, 1L, null, "금액 오입력 차감"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_ADMIN_ID));
    }

    @Test
    void 수동_조정에_사유가_비어_있으면_예외가_난다() {
        // when & then
        assertThatThrownBy(() -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -500L, 1L, 99L, "  "))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_ADJUSTMENT_REASON));
    }

    @Test
    void 조정_사유가_255자면_수동_조정이_생성된다() {
        // when (길이 검증에서 예외가 나면 여기서 테스트 실패)
        PayoutCandidateItem sut = PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -500L, 1L, 99L, "가".repeat(255));

        // then
        assertThat(sut.getReason()).hasSize(255);
    }

    @Test
    void 조정_사유가_255자를_넘으면_예외가_난다() {
        // when & then
        assertThatThrownBy(() -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -500L, 1L, 99L, "가".repeat(256)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.ADJUSTMENT_REASON_TOO_LONG));
    }

    @Test
    void 수동_조정에_조정_번호가_없으면_예외가_난다() {
        // when & then
        assertThatThrownBy(() -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -500L, null, 99L, "금액 오입력 차감"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.INVALID_ADJUSTMENT_ID));
    }

    @Test
    void 수동_조정에_평소_이벤트_전용_조정_번호_0이_오면_예외가_난다() {
        // when & then
        assertThatThrownBy(() -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -500L, 0L, 99L, "금액 오입력 차감"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.INVALID_ADJUSTMENT_ID));
    }
}
