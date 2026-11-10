package com.ok.payout.adapter.out.event;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.payout.adapter.out.event.dto.SellerPayoutCreatedMessage;
import com.ok.payout.application.port.out.SellerPayoutCreated;
import com.ok.payout.config.PayoutTopics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class KafkaPayoutEventPublisherTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 30, 14, 0);

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    KafkaPayoutEventPublisher sut;

    @Test
    void 키는_payoutId이고_값은_필드마다_같은_값을_담는다() {
        // given
        // 같은 Long 필드 3개에 서로 다른 값을 넣어, 필드 순서가 뒤바뀌면 실패하게 한다
        SellerPayoutCreated created = new SellerPayoutCreated(12L, 5L, 9_700L, CREATED_AT);
        given(kafkaTemplate.send(anyString(), anyString(), any())).willReturn(CompletableFuture.completedFuture(null));

        // when
        sut.publishSellerPayoutCreated(created);

        // then
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        then(kafkaTemplate).should().send(eq(PayoutTopics.SELLER_PAYOUT_CREATED), eq("12"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new SellerPayoutCreatedMessage(12L, 5L, 9_700L, CREATED_AT));
    }

    @Test
    void 지갑이_Kafka로_받기_전까지_같은_정산을_Spring_이벤트로도_발행한다() {
        // given
        SellerPayoutCreated created = new SellerPayoutCreated(12L, 5L, 9_700L, CREATED_AT);
        given(kafkaTemplate.send(anyString(), anyString(), any())).willReturn(CompletableFuture.completedFuture(null));

        // when
        sut.publishSellerPayoutCreated(created);

        // then
        then(applicationEventPublisher).should().publishEvent(new SellerPayoutCreatedEvent(12L, 5L, 9_700L, CREATED_AT));
    }

    @Test
    void 브로커_저장에_실패하면_payoutId를_담은_예외를_던진다() {
        // given
        SellerPayoutCreated created = new SellerPayoutCreated(12L, 5L, 9_700L, CREATED_AT);
        // 실제 브로커를 고장 낼 수 없어, 저장 확인이 실패로 끝난 결과를 돌려준다
        given(kafkaTemplate.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new KafkaException("브로커 저장 실패")));

        // when & then
        assertThatThrownBy(() -> sut.publishSellerPayoutCreated(created))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정산 지갑 이벤트 발행에 실패했습니다. payoutId=12");
        // Kafka에서 실패한 정산은 Spring 이벤트로도 보내지 않는다
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void 보내는_순간_오류가_나도_payoutId를_담은_예외를_던진다() {
        // given
        SellerPayoutCreated created = new SellerPayoutCreated(12L, 5L, 9_700L, CREATED_AT);
        // 직렬화 실패처럼 send()를 부르는 순간 바로 나는 오류
        given(kafkaTemplate.send(anyString(), anyString(), any()))
                .willThrow(new SerializationException("직렬화 실패"));

        // when & then
        assertThatThrownBy(() -> sut.publishSellerPayoutCreated(created))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정산 지갑 이벤트 발행에 실패했습니다. payoutId=12");
    }
}
