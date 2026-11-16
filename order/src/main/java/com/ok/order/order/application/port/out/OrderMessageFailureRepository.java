package com.ok.order.order.application.port.out;

import com.ok.order.order.domain.OrderMessageFailure;

import java.util.Optional;

/**
 * 주문 Kafka 실패 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface OrderMessageFailureRepository {

    OrderMessageFailure save(OrderMessageFailure failure);

    Optional<OrderMessageFailure> findByConsumerGroupAndTopicAndKafkaPartitionAndKafkaOffset(
            String consumerGroup, String topic, int kafkaPartition, long kafkaOffset);
}
