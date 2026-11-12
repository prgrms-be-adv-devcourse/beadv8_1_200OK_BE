package com.ok.member.auth.application.port.out;

/**
 * 회원이 승인된 판매자인지 읽는 출력 포트. 구현은 {@code adapter.out.member}에 있다.
 * 토큰에 SELLER 권한을 줄지 정하는 데 쓴다.
 */
public interface SellerApprovalReader {

    boolean isApprovedSeller(Long memberId);

}
