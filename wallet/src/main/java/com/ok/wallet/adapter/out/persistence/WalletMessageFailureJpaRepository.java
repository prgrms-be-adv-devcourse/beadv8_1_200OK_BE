package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.domain.WalletMessageFailure;
import org.springframework.data.repository.Repository;

// 실패 기록은 남기기만 하고 상태는 사람이 SQL로 바꾸므로 넣기와 있는지 확인만 둔다
public interface WalletMessageFailureJpaRepository extends Repository<WalletMessageFailure, Long> {

    boolean existsByTopicAndKafkaPartitionAndKafkaOffset(String topic, int kafkaPartition, long kafkaOffset);

    WalletMessageFailure save(WalletMessageFailure failure);
}
