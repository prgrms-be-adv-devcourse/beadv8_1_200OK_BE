package com.ok.wallet.application.dto;

/**
 * 복구 한 번의 결과. 이번에 맡아 토스에 확인한 건을 처리 결과별로 센다.
 * 다음에 다시 확인할 건(retried)에는 토스 답을 알 수 없었거나 반영 중 오류가 난 건도 들어간다.
 * 이미 끝난 건(alreadyHandled)은 토스에 확인하는 사이 늦게 온 승인 응답이 먼저 끝낸 건이다.
 */
public record ChargeRecoveryResult(int completed, int failed, int held, int retried, int alreadyHandled) {

    public int checked() {
        return completed + failed + held + retried + alreadyHandled;
    }
}
