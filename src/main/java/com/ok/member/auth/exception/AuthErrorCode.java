package com.ok.member.auth.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증(로그인·토큰) 관련 에러 코드를 정의한다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    // 아이디가 없는 경우와 비밀번호가 틀린 경우를 같은 코드로 응답해, 응답만으로 가입 여부를 알 수 없게 한다
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED,
            "Invalid credentials", "아이디 또는 비밀번호가 올바르지 않습니다."),
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "Member suspended", "이용이 정지된 회원입니다."),
    // 없음·만료·이미 사용함을 나누지 않는다. 클라이언트가 할 일은 모두 "다시 로그인"이고,
    // 원인을 알리면 그 토큰이 존재했었는지 드러난다
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED,
            "Invalid refresh token", "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    ;

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    @Override
    public String slug() {
        return "auth/" + ErrorCode.super.slug();
    }

}
