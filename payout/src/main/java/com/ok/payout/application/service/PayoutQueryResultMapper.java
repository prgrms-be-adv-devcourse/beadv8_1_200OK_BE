package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutAdjustmentResult;
import com.ok.payout.application.port.in.PayoutCandidateResult;
import com.ok.payout.application.port.in.PayoutItemResult;
import com.ok.payout.application.port.out.CandidateRow;
import com.ok.payout.application.port.out.PayoutItemRow;
import com.ok.payout.domain.PayoutCandidateItem;

/**
 * 조회 결과 한 줄(Row)을 입력 포트의 Result로 바꾸는 변환기.
 * 입력 포트(port.in)는 출력 포트(port.out)의 조회 Row 모양을 알면 안 되므로(이슈 #469, delivery 모듈과 같은 구조),
 * Row를 아는 서비스 쪽에 변환을 둔다. 조정 정보는 모두 담고, 판매자에게 보여 주지 않을 값은 판매자 응답 DTO에서 뺀다.
 */
final class PayoutQueryResultMapper {

    private PayoutQueryResultMapper() {
    }

    static PayoutItemResult toItemResult(PayoutItemRow row) {
        PayoutAdjustmentResult adjustment = null;
        // 평소 건은 adjustmentId가 0 (PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)
        if (row.adjustmentId() != PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID) {
            adjustment = new PayoutAdjustmentResult(row.adjustmentId(), row.adminId(), row.reason());
        }
        return new PayoutItemResult(row.payoutItemId(), row.candidateId(), row.eventType().name(), row.orderId(),
                row.orderItemId(), row.sellerId(), row.amount(), row.candidateCreatedAt(), adjustment);
    }

    static PayoutCandidateResult toCandidateResult(CandidateRow row) {
        PayoutAdjustmentResult adjustment = null;
        // 평소 건은 adjustmentId가 0 (PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)
        if (row.adjustmentId() != PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID) {
            adjustment = new PayoutAdjustmentResult(row.adjustmentId(), row.adminId(), row.reason());
        }
        return new PayoutCandidateResult(row.candidateId(), row.eventType().name(), row.orderId(), row.orderItemId(),
                row.sellerId(), row.amount(), row.candidateCreatedAt(), row.payoutId() != null, row.payoutId(),
                adjustment);
    }
}
