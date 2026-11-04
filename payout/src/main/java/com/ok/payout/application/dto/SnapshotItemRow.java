package com.ok.payout.application.dto;

import com.ok.payout.domain.PayoutEventType;

// verifyStep 결과 검증(#290)용: 후보 단위로 비교하려고 스냅샷·draft·실패 기록을 한 줄씩 가져옴 (판정은 PayoutDraftValidator)
// 저장소 어댑터(adapter/out/persistence)가 서비스 쪽 타입을 쓰면 레이어 규칙에 걸려서, 조회 결과용 record를 application/dto에 둠 (CandidateRow와 같은 이유)
// 스냅샷 항목 한 줄 = "원래 처리해야 할 후보". 금액은 스냅샷 시점 값, 주문·판매자·타입은 정산후보에서 가져옴
public record SnapshotItemRow(Long candidateId, Long orderId, Long sellerId, PayoutEventType eventType, Long amount) {
}
