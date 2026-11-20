package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 충전 건 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ChargeRepository {
    Charge save(Charge charge);

    Optional<Charge> findByChargeNo(String chargeNo);

    /**
     * 충전 건 행을 잠그고 읽는다. 처리 중인 건을 완료나 실패로 바꿀 때 쓴다.
     * 승인 응답과 복구, 또는 두 서버의 복구가 같은 건을 동시에 끝내려 하면 뒤에 온 쪽이 잠금을 기다렸다가 바뀐 상태를 읽는다.
     */
    Optional<Charge> findForUpdateByChargeNo(String chargeNo);

    /**
     * 대기 중인 충전 건만 처리 중으로 바꾸고 결제 키를 함께 저장한다. 바뀐 행 수를 반환한다.
     * 같은 건으로 승인 요청이 동시에 와도 한 요청만 1을 받아 토스 승인을 호출한다.
     * 결제 키를 이때 저장해, 승인 결과가 불명확하거나 반영에 실패해 처리 중으로 남아도 복구 시 어떤 결제인지 알 수 있다.
     *
     * @param nextCheckAt 처리 중으로 남으면 복구가 처음 토스에 확인할 시각
     */
    int markInProgress(String chargeNo, String paymentKey, LocalDateTime now, LocalDateTime nextCheckAt);

    /**
     * 대기 중인 충전 건만 실패로 바꾸고, 바뀐 행 수를 반환한다.
     * 승인이 먼저 처리 중으로 바꿨다면 0이 되어, 승인된 결제를 실패로 덮어쓰지 않는다.
     */
    int markFailedIfPending(String chargeNo, String failureCode, String failureMessage, LocalDateTime now);

    /**
     * 복구가 토스에 확인할 때가 된 처리 중 충전 건을 확인 시각이 이른 순으로 최대 limit개 읽는다.
     * 확인 시각이 비어 있는 건(사람 확인 대기)은 고르지 않는다.
     */
    List<Charge> findDueForRecovery(LocalDateTime now, int limit);

    /**
     * 확인할 때가 된 처리 중 충전 건이면 다음 확인 시각을 미루고 확인 횟수를 1 늘린다. 바뀐 행 수를 반환한다.
     * 두 서버의 복구가 같은 건을 함께 골라도, 먼저 바꾼 쪽이 확인 시각을 미루므로 뒤에 온 쪽은 0을 받아 토스에 묻지 않는다.
     */
    int claimForRecovery(String chargeNo, LocalDateTime now, LocalDateTime nextCheckAt);

    // 이 시각 전에 요청됐는데 아직 처리 중인 충전 번호를 오래된 순으로 최대 limit개 읽는다. 오래 남은 건을 알릴 때 쓴다.
    List<String> findInProgressChargeNosCreatedBefore(LocalDateTime createdBefore, int limit);

    /**
     * 처리 중인 충전 건의 확인 시각을 비워 복구가 더 고르지 않게 한다. 바뀐 행 수를 반환한다.
     * 토스 답만으로 판단할 수 없어 사람이 확인해야 하는 건에 쓴다.
     */
    int holdForReview(String chargeNo, LocalDateTime now);

    /**
     * 관리자가 처리할 충전 건을 요청 시각이 이른 순으로 최대 limit개 읽는다.
     * 복구가 멈춘 처리 중 건과, 실패로 처리된 뒤 토스가 승인해 아직 결제를 취소하지 않은 건이다.
     */
    List<Charge> findAdminTargets(int limit);

    // 회원의 특정 상태 충전 금액 합계. 잔액 보유 한도 확인에서 처리 중 충전을 함께 계산할 때 쓴다.
    long sumAmountByMemberIdAndStatus(Long memberId, ChargeStatus status);
}
