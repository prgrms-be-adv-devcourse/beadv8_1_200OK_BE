package com.ok.wallet.client.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 토스 승인 응답 중 승인 판단에 필요한 값만 받는다
@JsonIgnoreProperties(ignoreUnknown = true)
record TossConfirmResponse(String paymentKey, String orderId, String status, Long totalAmount) {
}
