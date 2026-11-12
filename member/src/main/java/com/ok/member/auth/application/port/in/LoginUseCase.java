package com.ok.member.auth.application.port.in;

import com.ok.member.auth.application.dto.LoginCommand;
import com.ok.member.auth.application.dto.TokenResult;

/**
 * 로그인 아이디와 비밀번호로 회원을 인증하고 Access Token과 Refresh Token을 발급하는 유스케이스.
 */
public interface LoginUseCase {

    /**
     * 로그인 아이디와 비밀번호를 확인하고 토큰을 발급한다.
     * Refresh Token은 해시로 저장되므로 원문은 반환값으로만 얻을 수 있다.
     *
     * @throws com.ok.common.exception.RestApiException 아이디가 없거나 비밀번호가 틀리거나 탈퇴한 회원이면
     *                                                  {@code INVALID_CREDENTIALS}, 정지된 회원이면 {@code MEMBER_SUSPENDED}
     */
    TokenResult login(LoginCommand command);

}
