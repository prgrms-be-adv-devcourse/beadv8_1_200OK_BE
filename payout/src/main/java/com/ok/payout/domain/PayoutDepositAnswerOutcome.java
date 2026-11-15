package com.ok.payout.domain;

// 지갑 답 하나를 대사 줄에 반영한 결과 (#619). 서비스가 다시 보내기·로그를 정할 때 씀
public enum PayoutDepositAnswerOutcome {
    // 가장 최근 질문의 답이 아니거나(늦게 온 답), 이미 처리한 답(같은 답이 두 번), 이미 끝난 줄이라 버림
    IGNORED,
    // 같은 판매자·금액으로 입금됨
    MATCHED,
    // "없음"이고 아직 물을 횟수가 남음. 입금 메시지를 다시 보냄
    NOT_FOUND_RETRY,
    // 관리자 확인 필요가 됨
    NEEDS_REVIEW
}
