package com.ok.payout.application.dto;

import com.ok.payout.domain.PayoutEventType;

import java.time.LocalDateTime;

// 정산후보 한 줄 + 정산됐으면 그 정산 ID (정산후보 조회용)
// 저장소 어댑터(adapter/out/persistence)가 서비스 쪽 타입을 쓰면 레이어 규칙에 걸려서, 조회 결과용 record를 application/dto에 둠 (PayoutItemRow와 같은 이유)
public record CandidateRow(
        Long candidateId,
        PayoutEventType eventType,
        Long orderId,
        Long orderItemId,                  // 배송비 후보는 null
        Long sellerId,
        Long amount,
        LocalDateTime candidateCreatedAt,  // 평소 건은 구매확정 직후, 조정 건은 조정한 시각
        // 0이면 평소 건, 1 이상이면 관리자 조정. Querydsl이 생성자를 타입으로 찾아서 엔티티 필드와 같은 Long으로 둠
        Long adjustmentId,
        Long adminId,                      // 조정 건일 때만 값
        String reason,                     // 조정 건일 때만 값
        Long payoutId                      // 정산된 후보면 그 정산 ID, 아직 정산 전이면 null
) {
}
