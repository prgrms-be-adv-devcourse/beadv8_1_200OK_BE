package com.ok.payout.domain;

// 막힌 주문 집계 표(#582)의 상태
public enum PayoutBlockedStatus {

    // 해결 안 된 잠그는 사유(수수료 불일치·결과 검증 불일치)가 있음. 실패 횟수와 상관없음
    LOCKED,

    // 잠기지 않았고, 성공한 실행에서 해결 안 된 실패가 PayoutPolicy.MAX_FAILED_RUNS번 이상 (#549)
    STOPPED,

    // 관리자가 해결함(#568). 다음 정산 Job이 끝날 때 다시 판정해, 풀렸으면 줄을 지우고 다시 막혔으면 LOCKED·STOPPED로 바꿈
    //   해결 직후 목록에 계속 "잠김"으로 보여 다른 관리자가 다시 해결하다 409를 받는 혼란을 없애려고 둠
    RESOLVED
}
