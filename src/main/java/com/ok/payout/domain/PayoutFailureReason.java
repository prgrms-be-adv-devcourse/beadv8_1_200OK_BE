package com.ok.payout.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// 정산 실패 사유 (#67 처리 방향 5번)
// locksOrder: 실패하면 이번 정산에서는 사유와 상관없이 항상 빠짐. 이 값은 "다음 배치부터도 계속 뺄지(잠금)"를 정함
//
// 주의: locksOrder는 실패 기록(payout_failure)에 저장하지 않고, 스냅샷을 만들 때마다 이 enum 값으로 잠금 여부를 판단함 (#195)
//   → 값을 바꾸면 이미 남은 과거 기록의 잠금 여부도 함께 바뀜 (예: true → false로 바꾸면 그동안 잠긴 주문이 다음 배치에서 한꺼번에 풀림)
//   지금은 바꿀 계획이 없어 저장하지 않음. 바꿀 일이 생기면 그때 locks_order 컬럼을 두어 기록 시점의 값을 저장하는 방식으로 전환
@Getter
@RequiredArgsConstructor
public enum PayoutFailureReason {

    // 지갑 차감 기록에 주문이 없음 → 이번 정산에서 빠짐
    // 잠그지 않음 = 다음 배치에서 다시 검사함 (그사이 차감 기록이 들어오면 정산, 아직 없으면 또 빠짐)
    // 잠그지 않는 이유: 지갑 이벤트가 늦게 처리돼 차감 기록이 나중에 들어올 수 있음
    DEDUCTION_MISSING(PayoutFailureStep.VALIDATE, false, "결제 기록 없음"),

    // 주문 생성 값이 없음. 정의만 해 둠 (주문상품 목록을 받으면 주문상품 단위 비교에 사용)
    ORDER_MISSING(PayoutFailureStep.VALIDATE, false, "주문 기록 없음"),

    // 금액 검사(초과 청구, 수수료)에 걸림 → 이번 정산에서 빠지고 그 주문을 잠금 (다음 배치부터도 그 주문의 후보는 스냅샷에 들어가지 않음)
    // 잠그는 이유: 입력 금액 자체가 틀린 것이라 기다려도 저절로 맞아지지 않음 (차감 기록 없음과 다름)
    // TODO(#52): 잠금 해제 경로가 없음. 풀기만 하면 다음 배치에서 다시 잠기므로, 금액 수정(조정)·해결 기록과 함께 환불 작업 때 처리 (#291)
    //   그 전까지 임시 해제 = 원인을 고친 뒤 그 주문의 이 사유 실패 기록 행을 수동으로 지움
    //   주의: 행을 지워도 스냅샷 조회 기간(마지막 정산일 − 대기·여유 일수 ~ 실행일 − 대기 일수)이 지난 후보는 다시 잡히지 않음
    //         매일 실행 기준 잠긴 뒤 약 3일이 지나면 해제만으로는 정산되지 않으므로, 실패 기반 재처리(#291)와 함께 다뤄야 함
    AMOUNT_MISMATCH(PayoutFailureStep.VALIDATE, true, "금액 불일치"),

    // 같은 주문의 다른 주문상품이 수수료 검사에 걸려 함께 보류됨 (이 후보 자체의 금액은 문제없음)
    // 잠그지 않음: 주문 잠금은 원인 후보의 AMOUNT_MISMATCH가 맡음. 이 사유까지 잠그면 원인이 풀려도 이 기록 때문에 계속 잠김
    ORDER_LOCKED(PayoutFailureStep.VALIDATE, false, "같은 주문의 금액 불일치로 보류");

    private final PayoutFailureStep step;
    private final boolean locksOrder;
    // 상세에 따로 남길 값이 없을 때 detail에 넣는 기본 문구 (detail은 NULL을 두지 않음)
    private final String defaultDetail;

    // 주문을 잠그는 사유 목록 (스냅샷 생성 시 "잠긴 주문 제외" 조건에 씀)
    // 쿼리에 사유를 직접 적지 않고 locksOrder에서 뽑아서, 잠그는 사유가 늘어도 쿼리를 고치지 않게 함
    public static List<PayoutFailureReason> orderLockingReasons() {
        List<PayoutFailureReason> reasons = new ArrayList<>();
        for (PayoutFailureReason reason : values()) {
            if (reason.locksOrder) {
                reasons.add(reason);
            }
        }
        return reasons;
    }
}
