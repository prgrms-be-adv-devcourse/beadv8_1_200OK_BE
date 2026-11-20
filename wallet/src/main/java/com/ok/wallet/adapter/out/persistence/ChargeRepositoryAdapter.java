package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.ChargeRepository;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 출력 포트 {@link ChargeRepository}를 {@link ChargeJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class ChargeRepositoryAdapter implements ChargeRepository {

    private final ChargeJpaRepository chargeJpaRepository;

    @Override
    public Charge save(Charge charge) {
        return chargeJpaRepository.save(charge);
    }

    @Override
    public Optional<Charge> findByChargeNo(String chargeNo) {
        return chargeJpaRepository.findByChargeNo(chargeNo);
    }

    @Override
    public Optional<Charge> findForUpdateByChargeNo(String chargeNo) {
        return chargeJpaRepository.findForUpdateByChargeNo(chargeNo);
    }

    @Override
    public int markInProgress(String chargeNo, String paymentKey, LocalDateTime now, LocalDateTime nextCheckAt) {
        return chargeJpaRepository.markInProgress(chargeNo, paymentKey, now, nextCheckAt);
    }

    @Override
    public int markFailedIfPending(String chargeNo, String failureCode, String failureMessage, LocalDateTime now) {
        return chargeJpaRepository.markFailedIfPending(chargeNo, failureCode, failureMessage, now);
    }

    @Override
    public List<Charge> findDueForRecovery(LocalDateTime now, int limit) {
        return chargeJpaRepository.findDueForRecovery(now, Limit.of(limit));
    }

    @Override
    public int claimForRecovery(String chargeNo, LocalDateTime now, LocalDateTime nextCheckAt) {
        return chargeJpaRepository.claimForRecovery(chargeNo, now, nextCheckAt);
    }

    @Override
    public List<String> findInProgressChargeNosCreatedBefore(LocalDateTime createdBefore, int limit) {
        return chargeJpaRepository.findInProgressChargeNosCreatedBefore(createdBefore, Limit.of(limit));
    }

    @Override
    public int holdForReview(String chargeNo, LocalDateTime now) {
        return chargeJpaRepository.holdForReview(chargeNo, now);
    }

    @Override
    public List<Charge> findAdminTargets(int limit) {
        return chargeJpaRepository.findAdminTargets(Limit.of(limit));
    }

    @Override
    public long sumAmountByMemberIdAndStatus(Long memberId, ChargeStatus status) {
        return chargeJpaRepository.sumAmountByMemberIdAndStatus(memberId, status);
    }
}
