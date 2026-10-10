package com.ok.payout.domain;

public class PayoutPolicy {

    // 수수료율 단위: 베이시스 포인트 (1bp = 0.01%, 10000bp = 100%)
    private static final long BASIS_POINT_SCALE = 10_000L;

    // 판매 수수료율 3.00%
    private static final long SALE_FEE_RATE_BP = 300L;

    // 시스템(플랫폼) 수취인 ID: 판매 수수료를 받는 계정
    public static final long SYSTEM_PAYEE_ID = 1L;   // TODO: 실제 시스템 계정 ID로 확정 (회의 안건)

    // 배치 정산 시 한 번에 처리하는 판매자 수
    public static final int PAYOUT_BATCH_PAYEE_SIZE = 100;   // TODO: 정책 값 확정 (#52)

    // 정산 후보 생성(구매확정) 후 정산 대상이 되기까지 대기 일수
    public static final int SETTLEMENT_WAITING_DAYS = 15;

    // 조회 하한 여유 일수 (누락 대비)
    public static final int SETTLEMENT_LOOKBACK_MARGIN_DAYS = 3;

    private PayoutPolicy() {
    }

    // 판매 수수료 계산 (원 미만 버림)
    // multiplyExact: long 범위를 넘으면 잘못된 값 대신 ArithmeticException으로 즉시 실패시킨다.
    // 판매 금액이 약 3경 원을 넘어야 발생하므로 현실적으로 일어나지 않아 별도 상한 검증은 두지 않는다.
    public static long calculateSaleFee(long saleAmount) {
        return Math.multiplyExact(saleAmount, SALE_FEE_RATE_BP) / BASIS_POINT_SCALE;
    }
}
