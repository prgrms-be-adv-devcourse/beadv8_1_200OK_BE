package com.ok.member.seller.service.dto;

import com.ok.member.seller.domain.SellerApplication;

import java.time.LocalDateTime;

/**
 * 관리자가 보는 판매자 신청 한 건. 회원용 결과({@link SellerApplicationResult})와 달리
 * 신청한 회원, 통신판매업 신고번호, 승인·거절을 결정한 관리자(심사자)까지 담는다.
 *
 * @param status     신청 상태 이름. 컨트롤러가 도메인 enum을 쓰지 않도록 문자열로 넘긴다
 * @param reviewerId 승인·거절을 결정한 관리자 회원 id. 결정 전에는 {@code null}
 */
public record SellerApplicationAdminResult(
        Long applicationId,
        Long memberId,
        String status,
        String storeName,
        String representativeName,
        String businessNumber,
        String mailOrderNumber,
        LocalDateTime appliedAt,
        LocalDateTime reviewStartedAt,
        LocalDateTime approvedAt,
        LocalDateTime rejectedAt,
        String rejectReason,
        Long reviewerId
) {

    public static SellerApplicationAdminResult from(SellerApplication application) {
        return new SellerApplicationAdminResult(
                application.getId(),
                application.getMemberId(),
                application.getStatus().name(),
                application.getStoreName(),
                application.getRepresentativeName(),
                application.getBusinessNumber(),
                application.getMailOrderNumber(),
                application.getAppliedAt(),
                application.getReviewStartedAt(),
                application.getApprovedAt(),
                application.getRejectedAt(),
                application.getRejectReason(),
                application.getReviewerId()
        );
    }

}
