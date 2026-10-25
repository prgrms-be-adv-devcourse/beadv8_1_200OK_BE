package com.ok.wallet.service.dto;

// 주문 결제 요청. 주문 생성 이벤트에서 리스너가 값을 꺼내 만든다.
public record OrderPaymentCommand(Long orderId, Long buyerId, Long amount) {
}
