package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.WalletMessageFailureRepository;
import com.ok.wallet.domain.WalletMessageFailure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 출력 포트 {@link WalletMessageFailureRepository}를 {@link WalletMessageFailureJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class WalletMessageFailureRepositoryAdapter implements WalletMessageFailureRepository {

    private final WalletMessageFailureJpaRepository walletMessageFailureJpaRepository;

    @Override
    public Optional<WalletMessageFailure> find(String topic, int kafkaPartition, long kafkaOffset) {
        return walletMessageFailureJpaRepository.findByTopicAndKafkaPartitionAndKafkaOffset(
                topic, kafkaPartition, kafkaOffset);
    }

    @Override
    public WalletMessageFailure save(WalletMessageFailure failure) {
        return walletMessageFailureJpaRepository.save(failure);
    }
}
