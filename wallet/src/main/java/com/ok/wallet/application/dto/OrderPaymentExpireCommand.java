package com.ok.wallet.application.dto;

// 주문의 결제 만료 요청. 주문이 결제를 요청하고 오래 결과를 받지 못하면 보낸다. 금액은 결제 요청과 같은 값이다
public record OrderPaymentExpireCommand(Long orderId, Long buyerId, Long amount) {
}
