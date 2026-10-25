package com.ok.payout.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 정산 실패 사유 (#67 처리 방향 5번)
// locksOrder: 실패하면 이번 정산에서는 사유와 상관없이 항상 빠짐. 이 값은 "다음 배치부터도 계속 뺄지(잠금)"를 정함 (잠금은 #195에서 사용)
@Getter
@RequiredArgsConstructor
public enum PayoutFailureReason {

    // 지갑 차감 기록에 주문이 없음 → 이번 정산에서 빠짐
    // 잠그지 않음 = 다음 배치에서 다시 검사함 (그사이 차감 기록이 들어오면 정산, 아직 없으면 또 빠짐)
    // 잠그지 않는 이유: 지갑 이벤트가 늦게 처리돼 차감 기록이 나중에 들어올 수 있음
    DEDUCTION_MISSING(PayoutFailureStep.VALIDATE, false, "결제 기록 없음"),

    // 주문 생성 값이 없음. 정의만 해 둠 (주문상품 목록을 받으면 주문상품 단위 비교에 사용)
    ORDER_MISSING(PayoutFailureStep.VALIDATE, false, "주문 기록 없음");

    private final PayoutFailureStep step;
    private final boolean locksOrder;
    // 상세에 따로 남길 값이 없을 때 detail에 넣는 기본 문구 (detail은 NULL을 두지 않음)
    private final String defaultDetail;
}
