package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayoutCandidateItemTest {

    @Test
    @DisplayName("판매 수수료(SALE_FEE)는 시스템 몫이다")
    void saleFee_isSystemShare() {
        // Arrange
        PayoutCandidateItem fee = PayoutCandidateItem.ofSaleFee(1L, 10L, 300L);

        // Act & Assert
        assertThat(fee.isSystemShare()).isTrue();
    }

    @Test
    @DisplayName("판매 대금·배송비는 판매자 몫이다 (시스템 몫 아님)")
    void saleAmountAndShippingFee_areNotSystemShare() {
        // Arrange
        PayoutCandidateItem amount = PayoutCandidateItem.ofSaleAmount(1L, 10L, 9_700L);
        PayoutCandidateItem shippingFee = PayoutCandidateItem.ofSaleShippingFee(1L, 10L, 3_000L);

        // Act & Assert
        assertThat(amount.isSystemShare()).isFalse();
        assertThat(shippingFee.isSystemShare()).isFalse();
    }

    @Test
    @DisplayName("수동 조정에 관리자 ID가 없으면 예외가 난다")
    void manualAdjustment_throwsException_whenAdminIdIsNull() {
        // Act & Assert
        RestApiException e = assertThrows(RestApiException.class, () -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, 1L, 10L, -500L, 1L, null, "금액 오입력 차감"));
        assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_ADMIN_ID);
    }

    @Test
    @DisplayName("수동 조정에 사유가 비어 있으면 예외가 난다")
    void manualAdjustment_throwsException_whenReasonIsBlank() {
        // Act & Assert
        RestApiException e = assertThrows(RestApiException.class, () -> PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, 1L, 10L, -500L, 1L, 99L, "  "));
        assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_ADJUSTMENT_REASON);
    }

    @Test
    @DisplayName("조정 번호가 없으면 0(평소 이벤트)으로 저장되고 관리자 ID·사유는 없어도 된다")
    void adjustmentIdIsZero_whenAdjustmentIdIsNull() {
        // Act (관리자 ID·사유 검증에서 예외가 나면 여기서 테스트 실패)
        PayoutCandidateItem item = PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, 1L, 10L, 9_700L, null, null, null);

        // Assert
        assertThat(item.getAdjustmentId()).isZero();
    }
}
