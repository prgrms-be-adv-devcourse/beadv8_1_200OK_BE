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
    REQUIRED_TERMS_NOT_AGREED(HttpStatus.UNPROCESSABLE_ENTITY, "Required terms not agreed", "필수 약관에 모두 동의해야 합니다."),
    TERMS_VERSION_OUTDATED(HttpStatus.CONFLICT, "Terms version outdated", "약관이 변경되었습니다. 최신 약관을 확인해 주세요."),
    TERMS_AGREEMENT_REQUIRED(HttpStatus.CONFLICT, "Terms agreement required", "충전 전 필수 약관 동의가 필요합니다."),
    INVALID_TERMS_TYPE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid terms type", "알 수 없는 약관 유형입니다."),
    INVALID_CHARGE_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid charge amount", "충전 금액은 10,000원 이상, 10,000원 단위여야 합니다."),
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
