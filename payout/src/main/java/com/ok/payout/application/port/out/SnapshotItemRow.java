package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutEventType;

// verifyStep 결과 검증(#290)용: 후보 단위로 비교하려고 스냅샷·draft·실패 기록을 한 줄씩 가져옴 (판정은 PayoutDraftValidator)
// 조회 포트(PayoutQueryRepository)가 돌려주는 결과 모양이라 포트와 같은 application/port/out에 둠. 서비스(PayoutService, PayoutDraftValidator)가 이 타입을 직접 읽고, 입력 포트(application/port/in)는 이 타입을 모름 (#469)
// 스냅샷 항목 한 줄 = "원래 처리해야 할 후보". 금액은 스냅샷 시점 값, 주문·판매자·타입은 정산후보에서 가져옴
public record SnapshotItemRow(Long candidateId, Long orderId, Long sellerId, PayoutEventType eventType, Long amount) {
}
