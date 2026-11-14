package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutAdjustmentResult;
import com.ok.payout.application.port.in.PayoutBlockedOrderCauseResult;
import com.ok.payout.application.port.in.PayoutBlockedOrderResult;
import com.ok.payout.application.port.in.PayoutCandidateResult;
import com.ok.payout.application.port.in.PayoutItemResult;
import com.ok.payout.application.port.in.PayoutResolutionRequestResult;
import com.ok.payout.application.port.in.PayoutResolutionResult;
import com.ok.payout.application.port.out.BlockedOrderRow;
import com.ok.payout.application.port.out.CandidateRow;
import com.ok.payout.application.port.out.PayoutItemRow;
import com.ok.payout.application.port.out.PayoutResolutionRequestRow;
import com.ok.payout.application.port.out.PayoutResolutionRow;
import com.ok.payout.domain.PayoutBlockedOrderCause;
import com.ok.payout.domain.PayoutCandidateItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 조회 결과 한 줄(Row)을 입력 포트의 결과 값(Result)으로 바꾼다.
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

    // 범주는 DB에 없고 사유가 들고 있어 여기서 꺼냄 (#391, PayoutFailureReason.category)
    static PayoutResolutionResult toResolutionResult(PayoutResolutionRow row) {
        return new PayoutResolutionResult(row.resolutionId(), row.resolutionRequestId(), row.failureId(), row.orderId(),
                row.orderItemId(), row.sellerId(), row.eventType().name(), row.amount(), row.reason().name(),
                row.reason().getCategory().name(), row.detail(), row.failedAt(), row.method().name(), row.adminId(),
                row.resolvedAt(), row.memo());
    }

    // 실패 기록 수는 주문 하나의 해결 기록 수라 int를 넘지 않음 (#568 해결 응답과 같은 int로 맞춤)
    static PayoutResolutionRequestResult toResolutionRequestResult(PayoutResolutionRequestRow row) {
        return new PayoutResolutionRequestResult(row.resolutionRequestId(), row.orderId(), row.method().name(),
                row.adminId(), row.resolvedAt(), row.memo(), Math.toIntExact(row.resolvedFailureCount()));
    }

    // 막힌 주문 1줄 + 그 주문의 원인들 (#569). 범주는 DB에 없고 사유가 들고 있어 여기서 꺼냄 (#391)
    // 판매자·사유·범주 목록은 원인에서 모아 중복을 뺌 (판매자는 작은 번호부터, 사유·범주는 원인 순서대로)
    static PayoutBlockedOrderResult toBlockedOrderResult(BlockedOrderRow row, List<PayoutBlockedOrderCause> causes) {
        Set<Long> sellerIds = new TreeSet<>();
        Set<String> reasons = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        List<PayoutBlockedOrderCauseResult> causeResults = new ArrayList<>();
        for (PayoutBlockedOrderCause cause : causes) {
            String category = cause.getReason().getCategory().name();
            sellerIds.add(cause.getSellerId());
            reasons.add(cause.getReason().name());
            categories.add(category);
            causeResults.add(new PayoutBlockedOrderCauseResult(cause.getOrderItemId(), cause.getEventType().name(),
                    cause.getReason().name(), category, cause.getDetail(), cause.getSellerId(), cause.getFailedAt()));
        }
        return new PayoutBlockedOrderResult(row.orderId(), row.status().name(), row.failedRunCount(),
                row.lastFailedAt(), new ArrayList<>(sellerIds), new ArrayList<>(reasons), new ArrayList<>(categories),
                causeResults);
    }
}
