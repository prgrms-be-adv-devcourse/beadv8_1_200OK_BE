package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.ChargeRepository;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
    public int markInProgress(String chargeNo, String paymentKey, LocalDateTime now) {
        return chargeJpaRepository.markInProgress(chargeNo, paymentKey, now);
    }

    @Override
    public int markFailedIfPending(String chargeNo, String failureCode, String failureMessage, LocalDateTime now) {
        return chargeJpaRepository.markFailedIfPending(chargeNo, failureCode, failureMessage, now);
    }

    @Override
    public long sumAmountByMemberIdAndStatus(Long memberId, ChargeStatus status) {
        return chargeJpaRepository.sumAmountByMemberIdAndStatus(memberId, status);
    }
}
