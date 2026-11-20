package com.ok.member.member.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "Duplicate login id", "이미 사용 중인 아이디입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "Duplicate email", "이미 사용 중인 이메일입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "Member not found", "회원 정보를 찾을 수 없습니다."),
    // 401로 응답하면 클라이언트가 토큰 문제로 보고 재로그인시키므로, 로그인은 유지한 채 다시 입력받도록 400으로 둔다
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "Invalid current password", "현재 비밀번호가 올바르지 않습니다."),
    SAME_AS_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "Same as current password", "새 비밀번호가 현재 비밀번호와 같습니다."),
    // 같은 회원의 비밀번호 변경이 동시에 들어와 다른 요청이 먼저 바꾼 경우. 이 요청이 확인한 현재 비밀번호는 더 이상 맞지 않는다
    PASSWORD_CHANGED_CONCURRENTLY(HttpStatus.CONFLICT,
            "Password changed concurrently", "비밀번호가 다른 요청으로 이미 변경되었습니다. 다시 로그인해 주세요."),
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
