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
    DEDUCTION_MISSING(PayoutFailureStep.VALIDATE, false, "결제 기록 없음", PayoutFailureCategory.SOURCE_DATA),

    // 주문 생성 값이 없음. 정의만 해 둠 (주문상품 목록을 받으면 주문상품 단위 비교에 사용)
    ORDER_MISSING(PayoutFailureStep.VALIDATE, false, "주문 기록 없음", PayoutFailureCategory.SOURCE_DATA),

    // 금액 검사 중 초과 청구(주문 후보 합계 > 지갑 차감액)에 걸림 → 이번 정산에서 빠지고 그 주문을 잠금 (다음 배치부터도 그 주문의 후보는 스냅샷에 들어가지 않음)
    // 수수료 검사에 걸린 것은 FEE_MISMATCH로 따로 기록함 (#391 전에는 이 사유에 함께 기록했음. 그때 저장된 행은 그대로 둠)
    // 잠그는 이유: 입력 금액 자체가 틀린 것이라 기다려도 저절로 맞아지지 않음 (차감 기록 없음과 다름)
    // TODO(#52): 잠금 해제 경로가 없음. 풀기만 하면 다음 배치에서 다시 잠기므로, 금액 수정(조정)·해결 기록과 함께 환불 작업 때 처리 (#291)
    //   그 전까지 임시 해제 = 원인을 고친 뒤 그 주문의 이 사유 실패 기록 행을 수동으로 지움
    //     (실패 기록은 원래 INSERT 전용이라 예외적인 운영 조치임. 지운 스냅샷은 "스냅샷 = draft + 실패" 등식이 더는 맞지 않음)
    //   주의: 행을 지워도 스냅샷 조회 기간(정산을 만든 마지막 정기 실행 요청일 − 대기·여유 일수 ~ 실행일 − 대기 일수)이 지난 후보는 다시 잡히지 않음
    //         매일 실행 기준 잠긴 뒤 약 3일이 지나면 해제만으로는 정산되지 않으므로, 실패 기반 재처리(#291)와 함께 다뤄야 함
    AMOUNT_MISMATCH(PayoutFailureStep.VALIDATE, true, "금액 불일치", PayoutFailureCategory.SOURCE_DATA),

    // 금액 검사 중 수수료 검사(원래 수수료 ≠ 수수료 계산)에 걸린 주문상품의 대금·수수료 후보 (#391에서 AMOUNT_MISMATCH와 나눔)
    // 나눈 이유: 초과 청구는 들어온 원천 데이터가 틀린 것이고, 수수료 불일치는 정상 경로에서는 생기지 않아 우리 쪽(시스템) 문제일 가능성이 큼
    //   → 원인 범주와 확인할 사람이 달라 사유만 보고 구분할 수 있게 함. 기록할 때 알림용 ERROR 로그도 남김 (PayoutService.validate)
    // 잠금(locksOrder=true)은 AMOUNT_MISMATCH와 같게 둠: #391 1단계(진단)는 돈 흐름을 바꾸지 않음. 잠금을 다르게 다루는 것은 2단계에서 정함
    //   잠금 조회는 orderLockingReasons()에서 사유를 뽑으므로 이 사유도 쿼리를 고치지 않고 잠금 조건에 들어감
    // 잠금 해제 경로·임시 해제·조회 기간 한계는 AMOUNT_MISMATCH 주석과 같음
    FEE_MISMATCH(PayoutFailureStep.VALIDATE, true, "수수료 불일치", PayoutFailureCategory.SYSTEM),

    // 같은 주문의 다른 주문상품이 수수료 검사에 걸려 함께 보류됨 (이 후보 자체의 금액은 문제없음)
    // 잠그지 않음: 주문 잠금은 원인 후보의 FEE_MISMATCH가 맡음. 이 사유까지 잠그면 원인이 풀려도 이 기록 때문에 계속 잠김
    // 이름(#391): 예전 이름 ORDER_LOCKED는 "이 후보가 주문을 잠갔다"로 읽혀, 잠그지 않는(locksOrder=false) 사유와 뜻이 어긋났음
    //   → "같은 주문의 다른 상품 때문에 함께 보류됨"이라는 뜻으로 ORDER_HELD로 바꿈. 이미 저장된 행은 마이그레이션으로 함께 바꿈
    //     (EnumType.STRING으로 이름이 저장되므로, 옛 이름이 남아 있으면 읽을 때 enum 변환에 실패함)
    // 범주가 SYSTEM인 이유: 이 사유는 수수료 불일치(FEE_MISMATCH) 때만 생겨서, 이 후보 자체가 아니라 원인을 따라 범주를 정함
    //   (초과 청구는 주문 후보 전부를 AMOUNT_MISMATCH로 기록해 이 사유를 쓰지 않음)
    ORDER_HELD(PayoutFailureStep.VALIDATE, false, "같은 주문의 금액 불일치로 보류", PayoutFailureCategory.SYSTEM),

    // draftStep에서 판매자 1명을 처리하다 예상하지 못한 예외가 남 (코드 버그, 일시적 DB 오류 등) → 그 판매자의 이번 스냅샷 후보 전체가 빠짐 (#90)
    // 원인 후보를 특정할 수 없어 판매자 단위로 기록하고, 상세에 예외 종류·메시지를 남김
    // 잠그지 않음 = 다음 배치에서 다시 시도함 (일시적 오류면 그때 정산, 같은 입력으로 계속 실패하면 배치마다 기록됨 → 반복 알림은 #52)
    //   단 다시 시도되는 건 스냅샷 조회 기간 안에 있는 동안뿐 (매일 실행이면 생성 후 약 16~19일째, 4번 정도)
    //   그 뒤로는 하한 밖으로 밀려 다시 잡히지 않음 → 조회 하한 밖 미정산 후보 재처리는 #291 (DEDUCTION_MISSING과 같은 한계)
    UNEXPECTED_ERROR(PayoutFailureStep.DRAFT, false, "예상하지 못한 오류", PayoutFailureCategory.SYSTEM),

    // verifyStep에서 draft가 스냅샷과 어긋난 후보를 찾음 (누락, 금액 다름, 스냅샷에 없는 항목 등) → 그 후보의 주문 전체가 빠지고 주문을 잠금 (#290)
    // 같은 주문이라 함께 빠진 정상 후보도 이 사유로 기록하고, 상세로 원인 후보와 구분함
    //   (#195의 ORDER_HELD처럼 사유를 나누지 않는 이유: verify에서 빠진 주문은 commitStep이 이 사유로 찾아 빼므로 사유 하나로 단순하게 둠)
    // 잠그는 이유: 입력 검증을 통과한 데이터로 우리 코드가 draft를 잘못 만든 것 = 코드 버그. 원인을 모르니 관리자가 확인할 때까지 그 주문을 정산하지 않음
    //   잠그지 않으면 버그가 남아 있는 동안 매 배치 같은 주문을 다시 처리함 (틀린 돈은 매번 verify가 막지만, 확인 없이 자동 재시도하는 것보다 멈춰 두는 편이 안전)
    // TODO(#52): 잠금 해제 경로가 없음 (AMOUNT_MISMATCH와 같음, #291). 임시 해제와 조회 기간 한계도 AMOUNT_MISMATCH 주석과 같음
    VERIFY_MISMATCH(PayoutFailureStep.VERIFY, true, "결과 검증 불일치", PayoutFailureCategory.SYSTEM);

    private final PayoutFailureStep step;
    private final boolean locksOrder;
    // 상세에 따로 남길 값이 없을 때 detail에 넣는 기본 문구 (detail은 NULL을 두지 않음)
    private final String defaultDetail;
    // 원인 범주 (#391). locksOrder처럼 DB에 저장하지 않고 이 enum 값으로 정함 → 값을 바꾸면 과거 기록의 범주도 함께 바뀜
    //   지금은 코드 안에서만 씀(API·로그에 아직 내보내지 않음. 실패 조회 API는 #301·#392)
    private final PayoutFailureCategory category;

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
