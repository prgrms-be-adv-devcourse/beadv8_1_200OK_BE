package com.ok.member.member.domain;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "Duplicate login id", "이미 사용 중인 아이디입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "Duplicate email", "이미 사용 중인 이메일입니다."),
    ;

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    /**
     * 다른 모듈의 에러코드와 type URI가 겹치지 않도록 모듈 접두사를 붙인다.
     * 예) https://.../problems/member/duplicate-email
     */
    @Override
    public String slug() {
        return "member/" + ErrorCode.super.slug();
    }
}
