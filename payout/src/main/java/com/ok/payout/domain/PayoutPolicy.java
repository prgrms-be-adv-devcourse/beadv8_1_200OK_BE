package com.ok.payout.domain;

import java.time.LocalDateTime;

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

    // 보류된 판매자의 후보를 다시 찾을 때 보는 draft 기간 (#291 결정 2번): 하한 기준일(PayoutService.settlementFrom: 정산을 만든 마지막 완료 정기 실행의 요청일, 없으면 마지막 정산일)부터 거꾸로 대기 일수의 두 배
    //   보류된 후보는 배치마다 다시 들어가 draft까지 가므로, 기준일이 된 실행의 draft에 늘 있음. 기준일은 배치가 멈춘 날 움직이지 않아 창도 그대로 기다림
    //   하한(대기 + 여유)보다 넓게 두는 이유: 그 주문이 며칠 금액 검사에 걸려 draft에서 빠져도 그 전 draft가 창 안에 남게 하려는 여유
    //   기준을 "오늘"로 두지 않는 이유: 배치가 창 길이만큼 연속으로 멈추면 보류된 후보를 기록 없이 놓침
    public static final int HELD_LOOKBACK_DAYS = SETTLEMENT_WAITING_DAYS * 2;

    private PayoutPolicy() {
    }

    // 하한(기준일 − 대기 − 여유)에서 보류 조회 창의 시작(기준일 − HELD_LOOKBACK_DAYS)을 구함
    // 하한이 없으면(첫 정산 전) 창도 없음 (null): 하한과 같이 처음부터 봄
    public static LocalDateTime heldLookbackFrom(LocalDateTime settlementFrom) {
        if (settlementFrom == null) {
            return null;
        }
        return settlementFrom.minusDays(HELD_LOOKBACK_DAYS - SETTLEMENT_WAITING_DAYS - SETTLEMENT_LOOKBACK_MARGIN_DAYS);
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
