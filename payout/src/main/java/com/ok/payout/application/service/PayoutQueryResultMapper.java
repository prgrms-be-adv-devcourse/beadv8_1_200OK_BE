package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutAdjustmentResult;
import com.ok.payout.application.port.in.PayoutBlockedOrderCauseResult;
import com.ok.payout.application.port.in.PayoutBlockedOrderResult;
import com.ok.payout.application.port.in.PayoutCandidateResult;
import com.ok.payout.application.port.in.PayoutDepositCheckReviewResult;
import com.ok.payout.application.port.in.PayoutItemResult;
import com.ok.payout.application.port.in.PayoutOverchargeEvidenceResult;
import com.ok.payout.application.port.in.PayoutResolutionRequestResult;
import com.ok.payout.application.port.in.PayoutResolutionResult;
import com.ok.payout.application.port.out.BlockedOrderRow;
import com.ok.payout.application.port.out.CandidateRow;
import com.ok.payout.application.port.out.DepositCheckReviewRow;
import com.ok.payout.application.port.out.PayoutItemRow;
import com.ok.payout.application.port.out.PayoutResolutionRequestRow;
import com.ok.payout.application.port.out.PayoutResolutionRow;
import com.ok.payout.domain.PayoutAdjustmentCheckStatus;
import com.ok.payout.domain.PayoutBlockedOrderCause;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutOverchargeEvidence;

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

    // 조정 상태를 채우지 않는 조회(기간 조회, 판매자 조회)용
    static PayoutCandidateResult toCandidateResult(CandidateRow row) {
        return toCandidateResult(row, null);
    }

    // 조정 상태(#791)는 관리자 주문 ID 조회만 채움. 상태가 없거나 평소 건이면 null
    static PayoutCandidateResult toCandidateResult(CandidateRow row, PayoutAdjustmentCheckStatus adjustmentCheckStatus) {
        PayoutAdjustmentResult adjustment = null;
        // 평소 건은 adjustmentId가 0 (PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)
        if (row.adjustmentId() != PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID) {
            adjustment = new PayoutAdjustmentResult(row.adjustmentId(), row.adminId(), row.reason());
        }
        String status = adjustment == null || adjustmentCheckStatus == null ? null : adjustmentCheckStatus.name();
        return new PayoutCandidateResult(row.candidateId(), row.eventType().name(), row.orderId(), row.orderItemId(),
                row.sellerId(), row.amount(), row.candidateCreatedAt(), row.payoutId() != null, row.payoutId(),
                adjustment, status);
    }

    // 조정 줄 하나의 상태 (#791): 저장소가 고른 확인됨·해결 대기 ID에 없으면 검사 밖
    // 검사 밖은 조건을 따로 쓰지 않고 나머지로 정함 (PayoutAdjustmentCheckStatus.UNCHECKED 주석)
    //   해결 대기 조건이 "확인됨 아님"을 포함해 두 ID 목록은 겹치지 않지만, 겹쳐도 확인됨을 먼저 봄
    static PayoutAdjustmentCheckStatus adjustmentCheckStatus(Long candidateId, Set<Long> confirmedIds, Set<Long> awaitingIds) {
        if (confirmedIds.contains(candidateId)) {
            return PayoutAdjustmentCheckStatus.CONFIRMED;
        }
        if (awaitingIds.contains(candidateId)) {
            return PayoutAdjustmentCheckStatus.AWAITING_RESOLUTION;
        }
        return PayoutAdjustmentCheckStatus.UNCHECKED;
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
    // 근거는 막힌 순간 = 표에 저장한 값(row), 지금 = 서비스가 볼 때 계산한 값(evidenceNow) (#570). 없으면 null
    static PayoutBlockedOrderResult toBlockedOrderResult(BlockedOrderRow row, List<PayoutBlockedOrderCause> causes,
                                                         PayoutOverchargeEvidence evidenceNow,
                                                         long pendingSaleAmountAdjustment, long pendingSaleFeeAdjustment) {
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
                causeResults, toEvidenceResult(row.overchargeEvidence()), toEvidenceResult(evidenceNow),
                pendingSaleAmountAdjustment, pendingSaleFeeAdjustment);
    }

    // 관리자 대사 확인 목록 1줄 (#621). 이유는 입구가 domain enum을 모르게 이름 문자열로 바꿈
    // 엔티티 칸 이름 lastCheckedAt은 "물은 시각"이라 결과에서는 뜻이 드러나게 lastAskedAt으로 옮김 (답 받은 시각으로 오해하지 않게)
    static PayoutDepositCheckReviewResult toDepositCheckReviewResult(DepositCheckReviewRow row) {
        return new PayoutDepositCheckReviewResult(row.payoutId(), row.payeeId(), row.amount(), row.walletPayeeId(),
                row.walletAmount(), row.mismatchReason().name(), row.attemptCount(), row.notFoundCount(),
                row.lastCheckedAt());
    }

    private static PayoutOverchargeEvidenceResult toEvidenceResult(PayoutOverchargeEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        return new PayoutOverchargeEvidenceResult(evidence.getDeductedAmount(), evidence.getCandidateTotal(),
                evidence.getOverflowAmount(), evidence.getFirstOverflowItem(), evidence.getSettledAmount(),
                evidence.settledItemList(), evidence.aloneOverItemList(), evidence.getSellerCount(),
                evidence.conflictAmountList());
    }
}
