package com.ok.order.cart.service.dto;

// merged: 이미 담긴 옵션에 수량을 더했는지. 응답 상태(새로 담음 201 / 수량 더함 200)를 나누는 데 쓴다
public record AddCartItemResult(Long cartItemId, int quantity, boolean merged) {
}
