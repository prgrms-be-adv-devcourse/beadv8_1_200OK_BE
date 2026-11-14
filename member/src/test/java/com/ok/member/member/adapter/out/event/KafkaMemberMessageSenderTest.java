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

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    // 본문 변환은 실제 JSON 변환기로 한다. outbox에 쓴 모양을 그대로 다시 읽는지까지 함께 본다
    MemberMessagePayloadJson payloadJson = new MemberMessagePayloadJson(JsonMapper.builder().build());

    KafkaMemberMessageSender sut;

    @BeforeEach
    void setUp() {
        sut = new KafkaMemberMessageSender(kafkaTemplate, payloadJson, SHORT_TIMEOUT);
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

    @Test
    void 출고지_우편번호는_저장해_둔_본문을_메시지로_읽어_판매자_키로_보낸다() {
        // given: outbox에 저장할 때와 같은 변환기로 만든 본문
        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 15, 10, 0, 0, 123_456_000);
        String payload = payloadJson.writeSellerOriginPostalCode(MEMBER_ID, "37421", occurredAt);
        SellerOriginPostalCodeMessage expected = new SellerOriginPostalCodeMessage(MEMBER_ID, "37421", occurredAt);
        // 키는 sellerId(7), 값은 문자열이 아니라 메시지 객체여야 한다. 문자열이면 Kafka 직렬화가 따옴표로 한 번 더 감싼다
        given(kafkaTemplate.send(eq(MemberTopics.SELLER_ORIGIN_POSTAL_CODE), eq("7"), eq(expected)))
                .willReturn(CompletableFuture.completedFuture(null));

        // when & then: given과 다른 토픽·키·값으로 보내면 결과가 null이라 예외가 난다
        assertThatCode(() -> sut.sendSellerOriginPostalCodeChanged(MEMBER_ID, payload)).doesNotThrowAnyException();
    }

    @Test
    void 출고지_우편번호_발송이_실패하면_sellerId를_담은_예외를_던진다() {
        // given
        String payload = payloadJson.writeSellerOriginPostalCode(MEMBER_ID, "37421", LocalDateTime.of(2026, 11, 15, 10, 0));
        KafkaException rejected = new KafkaException("브로커 거절");
        given(kafkaTemplate.send(eq(MemberTopics.SELLER_ORIGIN_POSTAL_CODE), eq("7"), any()))
                .willReturn(CompletableFuture.failedFuture(rejected));

        // when & then
        assertThatThrownBy(() -> sut.sendSellerOriginPostalCodeChanged(MEMBER_ID, payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("출고지 우편번호 Kafka 발행에 실패했습니다. sellerId=7")
                .hasCause(rejected);
    }

    @Test
    void 저장해_둔_본문을_읽지_못하면_발송하지_않고_예외를_던진다() {
        // when & then: 시스템이 쓴 값이라 읽지 못하면 버그다. 줄은 실패로 기록되고 다음 실행에서 다시 시도된다
        assertThatThrownBy(() -> sut.sendSellerOriginPostalCodeChanged(MEMBER_ID, "{깨진 JSON"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("출고지 우편번호 outbox 본문을 읽을 수 없습니다.");
    }

    // 키는 memberId(7)여야 같은 회원의 메시지가 같은 파티션으로 간다. 다른 키로 보내면 given이 맞지 않아 테스트가 실패한다
    private void givenSendReturns(CompletableFuture<SendResult<String, Object>> result) {
        given(kafkaTemplate.send(eq(MemberTopics.MEMBER_REGISTERED), eq("7"), eq(new MemberRegisteredMessage(MEMBER_ID))))
                .willReturn(result);
    }
}
