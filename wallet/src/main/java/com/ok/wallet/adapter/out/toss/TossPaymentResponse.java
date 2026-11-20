package com.ok.wallet.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 토스 결제 객체 중 판단에 필요한 값만 받는다. 승인 응답과 조회 응답이 같은 결제 객체라 함께 쓴다
@JsonIgnoreProperties(ignoreUnknown = true)
record TossPaymentResponse(String paymentKey, String orderId, String status, Long totalAmount) {
}
