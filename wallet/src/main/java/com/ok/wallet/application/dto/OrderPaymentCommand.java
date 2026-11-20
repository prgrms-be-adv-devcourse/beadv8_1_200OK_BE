package com.ok.wallet.application.dto;

// 주문 결제 요청. 주문 생성 이벤트에서 리스너가 값을 꺼내 만든다.
// orderSummary는 이용 내역에 보일 주문 요약으로, 결제에 쓰지 않아 비었거나 이상해도 결제는 한다
public record OrderPaymentCommand(Long orderId, Long buyerId, Long amount, String orderSummary) {
}
