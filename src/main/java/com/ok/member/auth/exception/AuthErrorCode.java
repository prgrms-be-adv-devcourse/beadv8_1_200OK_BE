package com.ok.member.auth.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증(로그인·토큰) 관련 에러 코드를 정의
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    // 아이디가 없는 경우와 비밀번호가 틀린 경우를 같은 코드로 응답해, 응답만으로 가입 여부를 알 수 없게 한다
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED,
            "Invalid credentials", "아이디 또는 비밀번호가 올바르지 않습니다."),
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "Member suspended", "이용이 정지된 회원입니다."),
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
        return "member/" + ErrorCode.super.slug();
    }

}
