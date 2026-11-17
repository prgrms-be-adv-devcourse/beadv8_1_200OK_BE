package com.ok.wallet.application.dto;

/**
 * 토스 결제 조회 결과. 복구는 이 세 가지만 보고 처리 중 충전 건을 어떻게 할지 정한다.
 * 결제가 없다는 답과 답을 못 받은 것을 나눈다. 앞의 것만 시간이 지나면 실패로 볼 근거가 된다.
 */
public sealed interface TossPaymentLookupResult {

    /**
     * 토스에 결제가 있다. 금액과 결제 키가 충전 건과 같은지는 호출하는 쪽이 맞춰 본다.
     *
     * @param paymentKey  토스가 가진 결제 키. 인증 전 결제는 비어 있을 수 있다
     * @param totalAmount 최초 결제 금액. 취소돼도 바뀌지 않는다
     */
    record Found(String paymentKey, TossPaymentStatus status, long totalAmount) implements TossPaymentLookupResult {
    }

    /** 토스가 결제가 없다고 답했다 ({@code NOT_FOUND_PAYMENT}). */
    record NotFound() implements TossPaymentLookupResult {
    }

    /** 답을 못 받았거나 믿을 수 없는 답이다. 충전 건을 바꾸지 않고 다음에 다시 묻는다. */
    record Unknown(String reason) implements TossPaymentLookupResult {
    }
}
