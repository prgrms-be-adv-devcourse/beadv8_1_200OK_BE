package com.ok.wallet.service.dto;

// 구매자 잔액과 잔액 보유 한도. 한도는 도메인 값이라 서비스가 담아 넘기고, 컨트롤러는 그대로 옮긴다.
public record BuyerBalanceResult(long balance, long maxBalance) {
}
