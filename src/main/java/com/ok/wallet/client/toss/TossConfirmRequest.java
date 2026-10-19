package com.ok.wallet.client.toss;

// 토스 승인 요청 본문. 토스는 우리의 chargeNo를 orderId라는 이름으로 받는다.
record TossConfirmRequest(String paymentKey, String orderId, long amount) {
}
