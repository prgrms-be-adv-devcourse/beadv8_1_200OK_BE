package com.ok.payout.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 정산 실패 사유 (#67 처리 방향 5번)
// locksOrder: 이 사유로 실패하면 그 주문을 다음 배치부터 정산 대상에서 뺄지 (잠금은 #195에서 사용)
@Getter
@RequiredArgsConstructor
public enum PayoutFailureReason {

    // 지갑 차감 기록에 주문이 없음
    // 잠그지 않음: 차감 기록이 늦게 들어올 수 있어, 미정산 후보로 남겨 다음 배치에서 다시 검사
    DEDUCTION_MISSING(PayoutFailureStep.VALIDATE, false, "결제 기록 없음"),

    // 주문 생성 값이 없음. 정의만 해 둠 (주문상품 목록을 받으면 주문상품 단위 비교에 사용)
    ORDER_MISSING(PayoutFailureStep.VALIDATE, false, "주문 기록 없음");

    private final PayoutFailureStep step;
    private final boolean locksOrder;
    // 상세에 따로 남길 값이 없을 때 detail에 넣는 기본 문구 (detail은 NULL을 두지 않음)
    private final String defaultDetail;
}
