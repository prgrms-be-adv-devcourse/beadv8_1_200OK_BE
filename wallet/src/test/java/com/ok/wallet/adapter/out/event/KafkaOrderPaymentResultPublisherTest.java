package com.ok.wallet.adapter.out.event;

import com.ok.wallet.adapter.out.event.dto.WalletPaymentCompletedMessage;
import com.ok.wallet.adapter.out.event.dto.WalletPaymentFailedMessage;
import com.ok.wallet.application.dto.OrderPaymentFailureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 어떤 토픽·키·값으로 보내는지와 실패했을 때 어느 주문인지 남는지를 본다.
// 실패 경로별 처리(시간 초과·중단 등)는 같은 WalletKafkaSender를 쓰는 KafkaWalletRefundResultPublisherTest가 본다.
// 실제 Kafka에 나간 JSON 모양은 Kafka 길 결제 처리 시험이 본다
@ExtendWith(MockitoExtension.class)
class KafkaOrderPaymentResultPublisherTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 15, 10, 30, 0, 123_456_000);

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    KafkaOrderPaymentResultPublisher sut;

    @BeforeEach
    void setUp() {
        sut = new KafkaOrderPaymentResultPublisher(new WalletKafkaSender(kafkaTemplate, Duration.ofMillis(50)));
    }

    @Test
    void 결제_완료는_주문_ID를_키로_완료_토픽에_보낸다() {
        // given
        WalletPaymentCompletedMessage message = new WalletPaymentCompletedMessage(10L, 1L, 3_000, PAID_AT);
        given(kafkaTemplate.send(WalletOutboundTopics.WALLET_PAYMENT_COMPLETED, "10", message))
                .willReturn(CompletableFuture.completedFuture(null));

        // when
        sut.publishPaymentCompleted(10L, 1L, 3_000, PAID_AT);

        // then: 키가 주문 ID여야 한 주문의 완료와 실패가 같은 파티션으로 간다
        then(kafkaTemplate).should().send(WalletOutboundTopics.WALLET_PAYMENT_COMPLETED, "10", message);
    }

    @Test
    void 결제_실패는_사유_이름을_담아_실패_토픽에_보낸다() {
        // given
        WalletPaymentFailedMessage message = new WalletPaymentFailedMessage(10L, 1L, "INSUFFICIENT_BALANCE");
        given(kafkaTemplate.send(WalletOutboundTopics.WALLET_PAYMENT_FAILED, "10", message))
                .willReturn(CompletableFuture.completedFuture(null));

        // when
        sut.publishPaymentFailed(10L, 1L, OrderPaymentFailureReason.INSUFFICIENT_BALANCE);

        // then: 사유 문자열이 주문과의 약속이다
        then(kafkaTemplate).should().send(WalletOutboundTopics.WALLET_PAYMENT_FAILED, "10", message);
    }

    @Test
    void 보내지_못하면_토픽과_주문_ID를_담은_예외를_던지고_원인을_남긴다() {
        // given
        KafkaException rejected = new KafkaException("브로커 거절");
        given(kafkaTemplate.send(WalletOutboundTopics.WALLET_PAYMENT_COMPLETED, "10",
                new WalletPaymentCompletedMessage(10L, 1L, 3_000, PAID_AT)))
                .willReturn(CompletableFuture.failedFuture(rejected));

        // when & then: 예외가 수신부까지 올라가야 같은 주문을 다시 받아 결과를 다시 보낸다
        assertThatThrownBy(() -> sut.publishPaymentCompleted(10L, 1L, 3_000, PAID_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 결제 결과를 보내지 못했습니다. topic=wallet.wallet-payment-completed.v1, orderId=10")
                .hasCause(rejected);
    }
}
