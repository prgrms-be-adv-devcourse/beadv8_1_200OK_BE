package com.ok.wallet.application.dto;

/**
 * 관리자가 처리를 고르기 전에 보는 충전 건과 토스 결제 상태.
 *
 * @param tossLookup      토스 조회 결과. {@code FOUND}, {@code NOT_FOUND}(결제 없음), {@code UNKNOWN}(답을 못 받음)
 * @param tossStatus      토스 결제 상태. 결제가 있을 때만 값이 있다
 * @param tossTotalAmount 토스 결제의 최초 금액. 결제가 있을 때만 값이 있다
 */
public record ChargeAdminDetailResult(
        ChargeAdminResult charge,
        String tossLookup,
        String tossStatus,
        Long tossTotalAmount
) {
    public static ChargeAdminDetailResult of(ChargeAdminResult charge, TossPaymentLookupResult lookup) {
        return switch (lookup) {
            case TossPaymentLookupResult.Found found ->
                    new ChargeAdminDetailResult(charge, "FOUND", found.status().name(), found.totalAmount());
            case TossPaymentLookupResult.NotFound notFound -> new ChargeAdminDetailResult(charge, "NOT_FOUND", null, null);
            case TossPaymentLookupResult.Unknown unknown -> new ChargeAdminDetailResult(charge, "UNKNOWN", null, null);
        };
    }
}
