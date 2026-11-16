package com.ok.payout.application.port.in;

// 관리자 대사 확인 목록 조회 조건 (#621, 페이징만)
// 상태·이유·기간 조건이 없는 이유: 목록은 관리자 확인 필요 줄만 보여 주고(사용자 결정), 그 줄은 사람이 봐야 할 건이라 오래된 것도 빠지면 안 됨
public record PayoutDepositCheckReviewSearchQuery(
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {
}
