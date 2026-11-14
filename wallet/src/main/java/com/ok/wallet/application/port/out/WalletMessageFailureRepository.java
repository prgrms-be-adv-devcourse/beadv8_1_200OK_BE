package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.WalletMessageFailure;

import java.util.Optional;

/**
 * 메시지 실패 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface WalletMessageFailureRepository {

    Optional<WalletMessageFailure> find(String topic, int kafkaPartition, long kafkaOffset);

    WalletMessageFailure save(WalletMessageFailure failure);
}
