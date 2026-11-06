package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 충전 건 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ChargeRepository {
    Charge save(Charge charge);

    Optional<Charge> findByChargeNo(String chargeNo);

    /**
     * 대기 중인 충전 건만 처리 중으로 바꾸고 결제 키를 함께 저장한다. 바뀐 행 수를 반환한다.
     * 같은 건으로 승인 요청이 동시에 와도 한 요청만 1을 받아 토스 승인을 호출한다.
     * 결제 키를 이때 저장해, 승인 결과가 불명확하거나 반영에 실패해 처리 중으로 남아도 복구 시 어떤 결제인지 알 수 있다.
     */
    int markInProgress(String chargeNo, String paymentKey, LocalDateTime now);

    /**
     * 대기 중인 충전 건만 실패로 바꾸고, 바뀐 행 수를 반환한다.
     * 승인이 먼저 처리 중으로 바꿨다면 0이 되어, 승인된 결제를 실패로 덮어쓰지 않는다.
     */
    int markFailedIfPending(String chargeNo, String failureCode, String failureMessage, LocalDateTime now);

    // 회원의 특정 상태 충전 금액 합계. 잔액 보유 한도 확인에서 처리 중 충전을 함께 계산할 때 쓴다.
    long sumAmountByMemberIdAndStatus(Long memberId, ChargeStatus status);
}
