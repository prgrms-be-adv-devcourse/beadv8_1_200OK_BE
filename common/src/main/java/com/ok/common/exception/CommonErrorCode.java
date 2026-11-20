package com.ok.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request", "잘못된 요청입니다."),
    INVALID_JSON(HttpStatus.BAD_REQUEST, "Invalid JSON", "요청 본문을 읽을 수 없습니다."),
    CONTENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "content too large", "요청 페이로드가 서버 허용 제한을 초과하였습니다."),
    INVALID_QUERY_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid query parameter", "잘못된 파라미터 형식입니다."),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized", "로그인이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired", "토큰이 만료되었습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid token", "유효하지 않은 토큰입니다."),
    // 토큰이 틀린 것이 아니라 검증할 공개키를 받아 오지 못한 경우다. 401과 구분해야 클라이언트가 재로그인으로 보내지 않는다
    AUTH_KEY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Auth key unavailable",
            "일시적으로 로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden", "접근 권한이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }
}
