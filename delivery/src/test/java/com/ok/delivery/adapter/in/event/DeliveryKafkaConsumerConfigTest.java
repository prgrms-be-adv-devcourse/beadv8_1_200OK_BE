package com.ok.delivery.adapter.in.event;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 단위 테스트: 배송 Kafka 오류 처리 규칙. DLT로 실제로 옮겨지는지는 SellerOriginPostalCodeKafkaIntegrationTest가 본다
class DeliveryKafkaConsumerConfigTest {

    @Test
    void 값이_틀려_던진_예외는_다시_시도하지_않는다() {
        // given
        DefaultErrorHandler sut = DeliveryKafkaConsumerConfig.deliveryErrorHandler(
                (record, consumer, e) -> { }, new FixedBackOff(0L, 0L));

        // when: removeClassification은 등록돼 있던 분류를 돌려준다 (false = 다시 시도하지 않음, null = 등록 안 됨)
        Boolean retryable = sut.removeClassification(IllegalArgumentException.class);

        // then
        assertThat(retryable).isFalse();
    }

    @Test
    void 일시적_실패는_1초부터_두_배씩_3번_다시_시도한다() {
        // given
        BackOffExecution sut = DeliveryKafkaConsumerConfig.deliveryBackOff().start();

        // when: 다음 대기 시간을 끝(STOP)이 나올 때까지 모은다
        List<Long> intervals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            intervals.add(sut.nextBackOff());
        }

        // then: 1·2·4초(합 7초) 뒤 더 시도하지 않고 DLT로 넘긴다
        assertThat(intervals).containsExactly(1_000L, 2_000L, 4_000L, BackOffExecution.STOP);
    }
}
