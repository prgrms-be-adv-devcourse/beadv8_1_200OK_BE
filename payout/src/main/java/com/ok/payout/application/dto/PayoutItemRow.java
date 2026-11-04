package com.ok.payout.application.dto;

import com.ok.payout.domain.PayoutEventType;

import java.time.LocalDateTime;

// 정산 항목 한 줄 + 그 항목의 정산후보 정보 (항목 조회용)
// 저장소 어댑터(adapter/out/persistence)가 서비스 쪽 타입을 쓰면 레이어 규칙에 걸려서, 조회 결과용 record를 application/dto에 둠 (CandidateRow·PayoutItemRow 같은 이유)
public record PayoutItemRow(
        Long payoutItemId,
        Long candidateId,
        PayoutEventType eventType,
        Long orderId,
        Long orderItemId,                  // 배송비 항목은 null
        Long sellerId,
        Long amount,
        LocalDateTime candidateCreatedAt,  // 정산후보 생성 시각
        // 0이면 평소 건, 1 이상이면 관리자 조정. 엔티티 필드와 같은 Long으로 둠 (Querydsl이 생성자를 타입으로 찾아서 long이면 못 찾음)
        Long adjustmentId,
        Long adminId,                      // 조정 건일 때만 값
        String reason                      // 조정 건일 때만 값
) {
}
