package com.ok.wallet.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WalletErrorCode implements ErrorCode {
    INVALID_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid amount", "금액은 0보다 커야 합니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "Insufficient balance", "잔액이 부족합니다."),
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
     * 예) https://.../problems/wallet/insufficient-balance
     */
    @Override
    public String slug() {
        return "wallet/" + ErrorCode.super.slug();
    }
}
