package com.ok.payout.service.dto;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.repository.PayoutQueryRepository.CandidateRow;

import java.time.LocalDateTime;

// 정산후보 1건. 서비스가 엔티티 대신 컨트롤러에 넘기는 값 (관리자·판매자 공용, 판매자에게 안 보일 값은 판매자 응답 DTO에서 뺌)
public record PayoutCandidateResult(
        // 정산후보 ID
        Long candidateId,
        // 항목 종류 (SALE_AMOUNT, SALE_SHIPPING_FEE, SALE_FEE). 컨트롤러는 domain enum을 쓸 수 없어 문자열로 넘김
        String eventType,
        // 주문 ID
        Long orderId,
        // 주문 항목 ID. 배송비 후보는 null
        Long orderItemId,
        // 판매자 회원 ID
        Long sellerId,
        // 금액 (원). 조정 건이면 음수일 수 있음
        Long amount,
        // 정산후보 생성 시각. 평소 건은 구매확정 직후, 조정 건은 조정한 시각 (구매확정 시각이 아님)
        LocalDateTime candidateCreatedAt,
        // 정산이 끝났는지 (정산 항목에 들어갔는지)
        boolean settled,
        // 정산된 후보면 그 정산 ID, 아직 정산 전이면 null. 수수료 후보면 시스템 몫 정산 ID
        Long payoutId,
        // 시스템(플랫폼) 몫 후보인지 (수수료). 판매자 응답에서 시스템 몫 정산 ID를 숨기는 데 씀
        boolean systemShare,
        // 관리자 조정 건이면 조정 정보, 평소 건이면 null
        PayoutAdjustmentResult adjustment
) {

    /**
     * 조회 결과 한 줄을 정산후보 결과로 바꾼다. 조정 정보는 모두 담고, 판매자에게 보여 주지 않을 값은 판매자 응답 DTO에서 뺀다.
     */
    public static PayoutCandidateResult from(CandidateRow row) {
        PayoutAdjustmentResult adjustment = null;
        // 평소 건은 adjustmentId가 0 (PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)
        if (row.adjustmentId() != PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID) {
            adjustment = new PayoutAdjustmentResult(row.adjustmentId(), row.adminId(), row.reason());
        }
        // 시스템 몫 판단은 PayoutCandidateItem.isSystemShare()와 같은 기준 (SALE_FEE만 시스템 몫). 이벤트 타입이 추가되면 함께 수정
        // 엔티티 메서드를 쓰지 않는 이유: 조회 결과(CandidateRow)는 엔티티가 아님. 한 곳으로 모으는 건 후보·정산 쪽 규칙이 더 갈라질 수 있어 보류
        boolean systemShare = row.eventType() == PayoutEventType.SALE_FEE;
        return new PayoutCandidateResult(row.candidateId(), row.eventType().name(), row.orderId(), row.orderItemId(),
                row.sellerId(), row.amount(), row.candidateCreatedAt(), row.payoutId() != null, row.payoutId(),
                systemShare, adjustment);
    }
}
