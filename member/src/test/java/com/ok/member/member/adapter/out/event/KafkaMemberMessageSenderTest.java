package com.ok.member.member.adapter.out.event;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

// 어댑터의 실패 경로를 본다. 성공 경로(실제 Kafka에 나간 키와 JSON)는 MemberRegisteredKafkaIntegrationTest가 본다.
// 실제 브로커로는 거절·시간 초과·중단을 만들기 어려워 KafkaTemplate을 Mockito로 대신한다.
// 예외가 나야 이벤트 발행 기록이 실패로 남아 다시 보내지므로, 경로마다 예외와 원인을 확인한다
@ExtendWith(MockitoExtension.class)
class KafkaMemberMessageSenderTest {

    private static final Long MEMBER_ID = 7L;
    private static final String FAILED_MESSAGE = "회원가입 Kafka 발행에 실패했습니다. memberId=7";
    // 시간 초과 경로를 빨리 확인하려는 짧은 대기 시간 (운영은 10초)
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    KafkaMemberMessageSender sut;

    @BeforeEach
    void setUp() {
        sut = new KafkaMemberMessageSender(kafkaTemplate, SHORT_TIMEOUT);
    }

    // 중단 테스트가 남긴 중단 표시가 다른 테스트로 넘어가지 않게 지운다
    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void 브로커가_거절하면_memberId를_담은_예외를_던지고_원인을_남긴다() {
        // given
        KafkaException rejected = new KafkaException("브로커 거절");
        givenSendReturns(CompletableFuture.failedFuture(rejected));

        // when & then
        assertThatThrownBy(() -> sut.sendMemberRegistered(MEMBER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE)
                .hasCause(rejected);
    }

    @Test
    void 정해진_시간_안에_확인이_오지_않으면_memberId를_담은_예외를_던진다() {
        // given: 끝나지 않는 결과(브로커가 답을 주지 않음)
        givenSendReturns(new CompletableFuture<>());

        // when & then
        assertThatThrownBy(() -> sut.sendMemberRegistered(MEMBER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void 기다리는_중_스레드가_중단되면_memberId를_담은_예외를_던지고_중단_표시를_되살린다() {
        // given: 이미 중단 표시가 있으면 끝나지 않은 결과를 기다리는 순간 InterruptedException이 난다
        givenSendReturns(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        // when & then
        assertThatThrownBy(() -> sut.sendMemberRegistered(MEMBER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE)
                .hasCauseInstanceOf(InterruptedException.class);
        // 중단 표시를 지우고 넘어가면 이 스레드를 쓰는 위쪽 코드가 중단 요청을 모른다
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void send를_부르는_순간_오류가_나도_memberId를_담은_예외를_던진다() {
        // given: 직렬화 실패나 보낼 자리 대기 시간 초과(max.block.ms)처럼 send() 안에서 바로 나는 오류
        KafkaException immediate = new KafkaException("보낼 자리 대기 시간 초과");
        given(kafkaTemplate.send(eq(MemberTopics.MEMBER_REGISTERED), eq("7"), eq(new MemberRegisteredMessage(MEMBER_ID))))
                .willThrow(immediate);

        // when & then
        assertThatThrownBy(() -> sut.sendMemberRegistered(MEMBER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE)
                .hasCause(immediate);
    }

    // 키는 memberId(7)여야 같은 회원의 메시지가 같은 파티션으로 간다. 다른 키로 보내면 given이 맞지 않아 테스트가 실패한다
    private void givenSendReturns(CompletableFuture<SendResult<String, Object>> result) {
        given(kafkaTemplate.send(eq(MemberTopics.MEMBER_REGISTERED), eq("7"), eq(new MemberRegisteredMessage(MEMBER_ID))))
                .willReturn(result);
    }
}
