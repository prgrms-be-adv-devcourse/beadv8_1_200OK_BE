package com.ok.payout.application.port.out;

// 판매자 draft 헤더 1건의 금액과 그 헤더에 묶인 항목 금액 합계
public record SellerTotalRow(Long sellerId, Long headerAmount, Long itemAmount) {
}
