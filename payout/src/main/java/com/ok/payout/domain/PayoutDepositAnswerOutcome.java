package com.ok.payout.domain;

// 지갑 답 하나를 대사 줄에 반영한 결과 (#619). 서비스가 다시 보내기·로그를 정할 때 씀
public enum PayoutDepositAnswerOutcome {
    // 가장 최근 질문의 답이 아닌 "없음"(늦게 온 답), 이미 처리한 답(같은 답이 두 번), 이미 끝난 줄에 온 답이라 버림
    IGNORED,
    // 같은 판매자·금액으로 입금됨
    MATCHED,
    // "없음"이고 아직 물을 횟수가 남음. outbox 줄을 다시 보낼 것(PENDING)으로 되돌려 짧은 간격 재발송이 입금 메시지를 다시 보냄 (#620)
    NOT_FOUND_RETRY,
    // 관리자 확인 필요가 됨
    NEEDS_REVIEW,
    // 3번째 답을 못 받고 시간이 다 되어 관리자 확인에 넘겼던 줄("입금 없음"·"지갑 답 없음")이 늦게 온 "있음, 같은 판매자·금액"으로 일치가 됨
    REVIEW_CLEARED
}
