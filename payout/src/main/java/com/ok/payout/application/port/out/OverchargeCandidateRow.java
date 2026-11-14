package com.ok.payout.application.port.out;

import java.time.LocalDateTime;

// 초과 청구 근거 계산용 정산후보 한 줄 (#391)
// 초과 청구에 걸린 주문의 정산후보 전체(스냅샷 밖·이미 정산 포함, 조정 제외 #552)를 생성 순서대로 읽은 결과
// 조회 포트(PayoutQueryRepository)가 돌려주는 결과 모양이라 포트와 같은 application/port/out에 둠 (CandidateRow와 같은 이유)
public record OverchargeCandidateRow(
        Long candidateId,
        Long orderId,
        Long orderItemId,          // 배송비 후보는 null
        Long sellerId,
        Long amount,
        LocalDateTime createdAt,
        boolean settled            // 이미 정산됨(payout_item에 이 후보가 있음)
) {
}
