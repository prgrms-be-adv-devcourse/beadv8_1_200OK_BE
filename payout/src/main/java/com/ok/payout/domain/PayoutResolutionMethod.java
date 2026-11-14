package com.ok.payout.domain;

// 실패 해결 방법 (#568)
// 지금은 "다시 처리"만 둠. DB에는 이름 문자열(VARCHAR)로 저장해, 나중에 값을 늘려도(정산 안 함, 조정과 함께 해결 등) 테이블을 바꾸지 않음
public enum PayoutResolutionMethod {

    // 다시 처리: 해결한 실패를 잠금 조건과 실패 횟수에서 빼, 다음 배치에서 금액 검사·결과 검증부터 다시 거치게 함
    REPROCESS
}
