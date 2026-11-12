package com.ok.member.auth.adapter.out.member;

import com.ok.member.auth.application.dto.AuthAccount;
import com.ok.member.auth.application.dto.LoginCredential;
import com.ok.member.auth.application.port.out.MemberCredentialReader;
import com.ok.member.auth.application.port.out.SellerApprovalReader;
import com.ok.member.member.application.dto.MemberAuthAccount;
import com.ok.member.member.application.port.in.MemberAuthAccountUseCase;
import com.ok.member.seller.application.port.in.SellerApprovalCheckUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 회원·판매자 하위 도메인의 유스케이스로 인증에 필요한 회원 정보를 읽는다.
 * 다른 하위 도메인 호출을 이 어댑터에만 두어, 회원·판매자 쪽 유스케이스나 DTO가 바뀌어도 인증 서비스는 바뀌지 않게 한다.
 * 회원과 판매자는 같은 member 모듈에 있어 하나의 어댑터로 묶는다.
 * 값만 옮기고 탈퇴·정지 같은 판단은 하지 않는다. 판단은 서비스가 한다.
 */
@Component
@RequiredArgsConstructor
public class MemberAuthAdapter implements MemberCredentialReader, SellerApprovalReader {

    private final MemberAuthAccountUseCase memberAuthAccountUseCase;
    private final SellerApprovalCheckUseCase sellerApprovalCheckUseCase;

    @Override
    public Optional<LoginCredential> findLoginCredential(String loginId) {
        return memberAuthAccountUseCase.findLoginCredential(loginId)
                .map(credential -> new LoginCredential(credential.memberId(), credential.passwordHash()));
    }

    @Override
    public Optional<AuthAccount> findAuthAccount(Long memberId) {
        return memberAuthAccountUseCase.findAuthAccount(memberId)
                .map(MemberAuthAdapter::toAuthAccount);
    }

    @Override
    public boolean isApprovedSeller(Long memberId) {
        return sellerApprovalCheckUseCase.isApprovedSeller(memberId);
    }

    private static AuthAccount toAuthAccount(MemberAuthAccount account) {
        return new AuthAccount(account.memberId(), account.admin(), account.withdrawn(), account.suspended(),
                account.passwordHash());
    }

}
