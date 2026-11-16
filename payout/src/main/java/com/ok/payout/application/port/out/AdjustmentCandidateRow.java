package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutCandidateItem;

// 아직 정산 안 된 조정 후보 한 줄과 그 원래 후보의 상태 (#291 결정 1번: 조정은 원래 후보를 따라 정산됨)
// 스냅샷을 만들 때 "원래가 이번 스냅샷에 들었는지"는 그때 정해지므로, 조회는 원래 후보 ID와 정산 여부만 돌려주고 판단은 서비스가 함
// 조회 포트(PayoutQueryRepository)가 돌려주는 결과 모양이라 포트와 같은 application/port/out에 둠 (OverchargeCandidateRow와 같은 이유)
public record AdjustmentCandidateRow(
        PayoutCandidateItem adjustment,
        Long originalCandidateId,  // 같은 주문상품·종류의 원래 후보(adjustment_id = 0) ID. 원래 후보가 없으면 null (생성이 막아 정상이면 없음)
        boolean originalSettled    // 원래 후보가 이미 정산됨(payout_item에 있음)
) {
}
