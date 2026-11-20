package com.ok.member.member.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.SignupCommand;

/**
 * 일반 회원 가입 유스케이스.
 */
public interface MemberSignupUseCase {

    /**
     * 일반 회원(구매자)으로 가입시키고 회원가입 이벤트를 발행한다.
     *
     * @return 만든 회원 ID
     * @throws RestApiException 아이디나 이메일이 이미 쓰이고 있으면 {@code DUPLICATE_LOGIN_ID}, {@code DUPLICATE_EMAIL}
     */
    Long signup(SignupCommand command);

}
