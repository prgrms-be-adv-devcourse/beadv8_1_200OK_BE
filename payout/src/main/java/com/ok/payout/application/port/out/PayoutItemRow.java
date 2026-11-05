package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutEventType;

import java.time.LocalDateTime;

// 정산 항목 한 줄 + 그 항목의 정산후보 정보 (항목 조회용)
// 조회 포트(PayoutQueryRepository)가 돌려주는 결과 모양이라 포트와 같은 application/port/out에 둠. 입력 포트의 결과 값(application/port/in의 *Result)은 이 타입을 읽기만 해서 의존이 port.in → port.out 한 방향으로 유지됨
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
