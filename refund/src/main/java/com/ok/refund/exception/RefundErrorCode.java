package com.ok.refund.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefundErrorCode implements ErrorCode {

    // 환불 요청
    INVALID_REFUND_QUANTITY(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid refund quantity", "취소 개수는 1 이상이어야 합니다.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    // 컨벤션의 type slug는 "<모듈>/<에러 코드 이름>"인데 기본 구현은 이름만 돌려주므로 모듈 접두사를 붙인다
    @Override
    public String slug() {
        return "refund/" + ErrorCode.super.slug();
    }
}
