package com.ok.member.member.application.port.in;

import com.ok.member.member.application.dto.MemberAuthAccount;
import com.ok.member.member.application.dto.MemberLoginCredential;

import java.util.Optional;

/**
 * 인증 하위 도메인이 로그인·토큰 재발급에 쓰는 회원 계정 조회 유스케이스.
 * 엔티티 대신 값만 돌려줘, 인증이 회원 엔티티와 저장소를 직접 읽지 않게 한다.
 * 호출하는 쪽 트랜잭션에 참여하므로 새 커넥션을 받지 않는다.
 */
public interface MemberAuthAccountUseCase {

    /**
     * 로그인 아이디로 비밀번호 확인에 필요한 값을 조회한다.
     * 탈퇴 회원도 그대로 돌려준다. 틀린 비밀번호에는 회원 상태와 관계없이 같은 응답을 주도록, 상태는 비밀번호를 확인한 뒤
     * {@link #findAuthAccount}로 판단한다.
     */
    Optional<MemberLoginCredential> findLoginCredential(String loginId);

    /**
     * 토큰을 발급하기 직전에 회원의 현재 계정 상태를 조회한다. 탈퇴·정지 회원도 그대로 돌려주고, 판단은 호출하는 쪽이 한다.
     */
    Optional<MemberAuthAccount> findAuthAccount(Long memberId);

}
