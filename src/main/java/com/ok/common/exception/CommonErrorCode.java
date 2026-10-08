package com.ok.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request", "잘못된 요청입니다."),
    INVALID_JSON(HttpStatus.BAD_REQUEST, "Invalid JSON", "요청 본문을 읽을 수 없습니다."),
    INVALID_QUERY_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid query parameter", "잘못된 파라미터 형식입니다."),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "요청 값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }
}
