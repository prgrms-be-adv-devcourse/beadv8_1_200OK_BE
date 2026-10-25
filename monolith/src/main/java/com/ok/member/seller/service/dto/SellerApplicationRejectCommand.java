package com.ok.member.seller.service.dto;

/**
 * 판매자 신청 거절 입력. 값은 요청 DTO에서 정규화·검증을 마친 상태다.
 *
 * @param reason 거절 사유. 신청한 회원에게 그대로 보인다
 */
public record SellerApplicationRejectCommand(
        String reason
) {
}
