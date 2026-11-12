package com.ok.payout.domain;

// 정산 실패 사유의 원인 범주 (#391)
// 사유(PayoutFailureReason)보다 한 단계 큰 묶음으로, "누가 무엇을 확인해야 하는지"를 사유 목록을 외우지 않고 알 수 있게 함
// DB에 저장하지 않음: 사유마다 정해진 값이라 PayoutFailureReason이 들고 있음 (locksOrder와 같은 방식)
public enum PayoutFailureCategory {

    // 원천 데이터 문제: 다른 모듈에서 받은 값(지갑 차감, 구매확정 금액 등)이 없거나 서로 맞지 않음
    // 확인할 곳 = 값을 보낸 쪽 데이터
    SOURCE_DATA,

    // 시스템 문제: 정상 경로에서는 생기지 않는 상태라 우리 코드·설정·일시적 오류가 원인일 가능성이 큼
    // 확인할 곳 = 정산 코드와 실행 환경
    SYSTEM,

    // 대사 문제: 정산 결과와 외부 기록(지갑 지급 등)을 맞춰 볼 때 어긋남
    // #308(대사)용으로 미리 둠. 지금 이 범주를 쓰는 사유는 없음
    RECONCILIATION
}
