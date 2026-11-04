package com.ok.payout.application.dto;

// 건수·금액 합계를 함께 담는 값 (정산 상세의 판매자 수수료 집계)
//   예) CountAndAmount(2, 900) = 2건, 900원
public record CountAndAmount(long count, long amount) {
}
