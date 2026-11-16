package com.ok.member.auth.application.port.out;

import com.ok.member.auth.application.dto.AuthAccount;
import com.ok.member.auth.application.dto.LoginCredential;

import java.util.Optional;

/**
 * 로그인·토큰 재발급에 필요한 회원 계정을 읽는 출력 포트. 구현은 {@code adapter.out.member}에 있다.
 * 서비스가 회원 하위 도메인의 타입을 모르도록, 회원 하위 도메인은 구현(어댑터)만 부른다.
 * 호출하는 쪽 트랜잭션에 참여한다.
 */
public interface MemberCredentialReader {

    /**
     * 로그인 아이디로 비밀번호 확인에 필요한 값을 조회한다.
     * 탈퇴 회원도 그대로 돌려준다. 틀린 비밀번호에는 회원 상태와 관계없이 같은 응답을 주도록, 상태는 비밀번호를 확인한 뒤
     * {@link #findAuthAccount}로 판단한다.
     */
    Optional<LoginCredential> findLoginCredential(String loginId);

    /**
     * 토큰을 발급하기 직전에 회원의 현재 계정 상태를 조회한다. 탈퇴·정지 회원도 그대로 돌려주고, 판단은 호출하는 쪽이 한다.
     */
    Optional<AuthAccount> findAuthAccount(Long memberId);

}
