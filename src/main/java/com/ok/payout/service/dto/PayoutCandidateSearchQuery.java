package com.ok.payout.service.dto;

import java.time.LocalDateTime;

// 관리자 정산후보 목록 조회 조건. 판매자용은 PayoutSellerCandidateSearchQuery
public record PayoutCandidateSearchQuery(
        // 판매자 회원 ID. 생략하면 전체 판매자
        Long sellerId,
        // 후보 생성 시각 하한, 포함 (평소 건은 구매확정 직후, 조정 건은 조정한 시각)
        LocalDateTime from,
        // 후보 생성 시각 상한, 미포함
        LocalDateTime to,
        // true면 아직 정산 전인 후보만, false면 전부
        boolean unsettledOnly,
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {
}
