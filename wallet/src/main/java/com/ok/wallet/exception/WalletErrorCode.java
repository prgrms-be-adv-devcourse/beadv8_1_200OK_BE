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
    INVALID_CHARGE_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid charge amount", "충전 금액은 10,000원 이상이어야 합니다."),
    BALANCE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Balance limit exceeded", "충전 후 잔액은 500,000원을 넘을 수 없습니다."),
    CHARGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Charge not found", "충전 내역을 찾을 수 없습니다."),
    CHARGE_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Charge amount mismatch", "충전 요청 금액과 결제 금액이 다릅니다."),
    PAYMENT_KEY_ALREADY_USED(HttpStatus.CONFLICT, "Payment key already used", "이미 다른 충전에 사용된 결제입니다."),
    CHARGE_NOT_ADMIN_HANDLEABLE(HttpStatus.CONFLICT, "Charge not admin handleable", "관리자가 처리할 수 있는 충전 건이 아닙니다."),
    CHARGE_TOSS_STATUS_MISMATCH(HttpStatus.CONFLICT, "Charge toss status mismatch", "토스 결제 상태와 맞지 않는 처리입니다."),
    CHARGE_TOSS_STATUS_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "Charge toss status unknown", "토스 결제 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    CHARGE_PAYMENT_CANCEL_REJECTED(HttpStatus.CONFLICT, "Charge payment cancel rejected", "토스가 결제 취소를 거절했습니다."),
    INVALID_LEDGER_PERIOD(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid ledger period", "조회 시작일은 종료일보다 늦을 수 없습니다."),
    UNSUPPORTED_LEDGER_TYPE(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported ledger type", "지원하지 않는 이용 내역 유형입니다."),
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
