package com.ok.wallet.adapter.out.event;

import com.ok.wallet.application.dto.PayoutDepositCheckResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

// 보내기 실패 경로(시간 초과·중단 등)는 함께 쓰는 WalletKafkaSender를 KafkaWalletRefundResultPublisherTest가 본다.
// 여기서는 실패했을 때 어느 묶음의 답인지 예외에 남는지만 본다. 성공 경로(실제 Kafka에 나간 JSON)는 WalletPayoutDepositCheckServiceIntegrationTest가 본다
@ExtendWith(MockitoExtension.class)
class KafkaPayoutDepositCheckResultPublisherTest {

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void 답을_보내지_못하면_묶음_번호를_담은_예외를_던지고_원인을_남긴다() {
        // given: 키는 checkId(3)여야 한다. 다른 키로 보내면 given이 맞지 않아 테스트가 실패한다
        KafkaException rejected = new KafkaException("브로커 거절");
        given(kafkaTemplate.send(eq(WalletOutboundTopics.PAYOUT_DEPOSIT_CHECKED), eq("3"), any()))
                .willReturn(CompletableFuture.failedFuture(rejected));
        given(kafkaTemplate.send(eq(WalletOutboundTopics.PAYOUT_DEPOSIT_CHECKED_OLD), eq("3"), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaPayoutDepositCheckResultPublisher sut =
                new KafkaPayoutDepositCheckResultPublisher(new WalletKafkaSender(kafkaTemplate, Duration.ofMillis(50)));
        PayoutDepositCheckResult result = new PayoutDepositCheckResult(3L, List.of(PayoutDepositCheckResult.Item.notFound(15L)));

        // when & then
        assertThatThrownBy(() -> sut.publish(result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정산 입금 확인 답을 보내지 못했습니다. checkId=3, topic=wallet.payout-deposit-checked.v1")
                .hasCause(rejected);
    }
}
