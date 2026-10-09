package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;

public class PayoutPolicy {

    // 수수료율 단위: 베이시스 포인트 (1bp = 0.01%, 10000bp = 100%)
    private static final long BASIS_POINT_SCALE = 10_000L;

    // 판매 수수료율 3.00%
    private static final long SALE_FEE_RATE_BP = 300L;

    private PayoutPolicy() {
    }

    // 판매 수수료 계산 (원 미만 버림)
    public static long calculateSaleFee(long saleAmount) {

        return Math.multiplyExact(saleAmount, SALE_FEE_RATE_BP) / BASIS_POINT_SCALE;
    }
}
