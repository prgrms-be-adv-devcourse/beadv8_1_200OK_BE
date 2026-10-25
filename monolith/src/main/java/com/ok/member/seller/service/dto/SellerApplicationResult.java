package com.ok.member.seller.service.dto;

import com.ok.member.seller.domain.SellerApplication;

import java.time.LocalDateTime;

/**
 * 판매자 신청 한 건의 신청 정보와 심사 진행 일시.
 *
 * @param status       신청 상태 이름. 컨트롤러가 도메인 enum을 쓰지 않도록 문자열로 넘긴다
 * @param rejectReason 거절 사유. 거절된 신청에만 있다. 심사자 id는 관리자용 정보라 담지 않는다
 */
public record SellerApplicationResult(
        Long applicationId,
        String status,
        String storeName,
        String representativeName,
        String businessNumber,
        LocalDateTime appliedAt,
        LocalDateTime reviewStartedAt,
        LocalDateTime approvedAt,
        LocalDateTime rejectedAt,
        String rejectReason
) {

    public static SellerApplicationResult from(SellerApplication application) {
        return new SellerApplicationResult(
                application.getId(),
                application.getStatus().name(),
                application.getStoreName(),
                application.getRepresentativeName(),
                application.getBusinessNumber(),
                application.getAppliedAt(),
                application.getReviewStartedAt(),
                application.getApprovedAt(),
                application.getRejectedAt(),
                application.getRejectReason()
        );
    }

}
