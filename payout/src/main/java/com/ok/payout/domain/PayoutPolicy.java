package com.ok.payout.domain;

public class PayoutPolicy {

    // 수수료율 단위: 베이시스 포인트 (1bp = 0.01%, 10000bp = 100%)
    private static final long BASIS_POINT_SCALE = 10_000L;

    // 판매 수수료율 3.00%
    private static final long SALE_FEE_RATE_BP = 300L;

    // 배치 정산 시 한 번에 처리하는 판매자 수
    public static final int PAYOUT_BATCH_PAYEE_SIZE = 100;   // TODO: 정책 값 확정 (#52)

    // 정산 후보 생성(구매확정) 후 정산 대상이 되기까지 대기 일수
    public static final int SETTLEMENT_WAITING_DAYS = 15;

    // 조회 하한 여유 일수 (누락 대비)
    public static final int SETTLEMENT_LOOKBACK_MARGIN_DAYS = 3;

    // 한 주문이 성공한 실행에서 실패 기록을 남길 수 있는 최대 횟수 (#549)
    // 실패가 이 횟수보다 적으면 조회 기간 밖이어도 다시 검사하고, 처음 검사를 포함해 이 횟수만큼 실패하면 멈춤: 고칠 수 없는 오류가 매일 반복 검사되는 것을 막으면서,
    //   일시적인 오류(지갑 차감 기록이 늦게 도착 등)는 몇 번 더 기회를 주려는 것
    public static final int MAX_FAILED_RUNS = 3;

    private PayoutPolicy() {
    }

    // 실패 횟수로 본 멈춤 판정 (#549). 스냅샷·해결 API·막힌 주문 집계 표(#582)가 함께 씀
    // 비교를 곳곳에 따로 쓰면 기준을 바꿀 때 한 곳을 빠뜨려 셋이 같은 주문에 다른 답을 낼 수 있어 한곳에 둠
    public static boolean isStopped(long failedRuns) {
        return failedRuns >= MAX_FAILED_RUNS;
    }

    // 판매 수수료 계산 (원 미만 버림)
    // multiplyExact: long 범위를 넘으면 잘못된 값 대신 ArithmeticException으로 즉시 실패시킨다.
    // 판매 금액이 약 3경 원을 넘어야 발생하므로 현실적으로 일어나지 않아 별도 상한 검증은 두지 않는다.
    public static long calculateSaleFee(long saleAmount) {
        return Math.multiplyExact(saleAmount, SALE_FEE_RATE_BP) / BASIS_POINT_SCALE;
    }
}
