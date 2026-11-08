package com.ok.member.seller.application.port.in;

/**
 * 회원이 승인된 판매자인지 확인하는 유스케이스. 인증 하위 도메인이 토큰 권한을 정할 때 쓴다.
 */
public interface SellerApprovalCheckUseCase {

    /**
     * 승인된 판매자인지 확인한다. 토큰을 발급할 때 SELLER 권한을 줄지 정하는 데 쓴다.
     * 판매자 여부를 판단하는 규칙을 판매자 쪽에 두어, 다른 하위 도메인(인증)이 신청 데이터를 직접 읽지 않게 한다.
     */
    boolean isApprovedSeller(Long memberId);

}
