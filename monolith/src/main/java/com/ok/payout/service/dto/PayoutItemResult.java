package com.ok.payout.service.dto;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.repository.PayoutQueryRepository.PayoutItemRow;

import java.time.LocalDateTime;

// 정산 항목 1건 (정산후보 정보 포함). 서비스가 엔티티 대신 컨트롤러에 넘기는 값
public record PayoutItemResult(
        // 정산 항목 ID (payout_item.id)
        Long payoutItemId,
        // 이 항목이 된 정산후보 ID
        Long candidateId,
        // 항목 종류 (SALE_AMOUNT, SALE_SHIPPING_FEE, SALE_FEE). 컨트롤러는 domain enum을 쓸 수 없어 문자열로 넘김
        String eventType,
        // 주문 ID
        Long orderId,
        // 주문 항목 ID. 배송비 항목은 null (배송비는 상품이 아니라 판매자 묶음 배송 1건의 금액)
        Long orderItemId,
        // 판매자 회원 ID. 시스템 몫(수수료) 항목이면 어느 판매자 판매에서 나온 수수료인지
        Long sellerId,
        // 항목 금액 (원). 조정 건이면 음수일 수 있음
        Long amount,
        // 정산후보 생성 시각. 평소 건은 구매확정 직후, 조정 건은 조정한 시각 (구매확정 시각이 아님)
        LocalDateTime candidateCreatedAt,
        // 관리자 조정 건이면 조정 정보, 평소 건이면 null
        PayoutAdjustmentResult adjustment
) {

    /**
     * 조회 결과 한 줄을 항목 결과로 바꾼다. 조정 정보는 모두 담고, 판매자에게 보여 주지 않을 값은 판매자 응답 DTO에서 뺀다.
     */
    public static PayoutItemResult from(PayoutItemRow row) {
        PayoutAdjustmentResult adjustment = null;
        // 평소 건은 adjustmentId가 0 (PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)
        if (row.adjustmentId() != PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID) {
            adjustment = new PayoutAdjustmentResult(row.adjustmentId(), row.adminId(), row.reason());
        }
        return new PayoutItemResult(row.payoutItemId(), row.candidateId(), row.eventType().name(), row.orderId(),
                row.orderItemId(), row.sellerId(), row.amount(), row.candidateCreatedAt(), adjustment);
    }
}
