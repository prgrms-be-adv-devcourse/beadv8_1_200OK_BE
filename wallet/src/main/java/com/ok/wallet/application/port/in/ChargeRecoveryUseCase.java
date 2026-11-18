package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.ChargeRecoveryResult;

import java.util.List;

/**
 * 처리 중으로 멈춘 충전 건 복구 유스케이스.
 */
public interface ChargeRecoveryUseCase {

    /**
     * 확인할 때가 된 처리 중 충전 건을 토스에 조회해 완료하거나 실패로 바꾼다.
     * 토스 답만으로 판단할 수 없는 건은 멈추고, 아직 알 수 없는 건은 다음에 다시 확인한다.
     */
    ChargeRecoveryResult recover();

    /**
     * 복구가 끝냈어야 할 만큼 오래 처리 중으로 남은 충전 번호를 읽는다.
     * 사람 확인을 기다리는 건과, 복구가 돌지 않거나 계속 실패해 남은 건이 함께 나온다.
     */
    List<String> findLongInProgressChargeNos();
}
