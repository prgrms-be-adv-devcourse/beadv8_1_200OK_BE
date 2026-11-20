package com.ok.member.member;

import com.ok.member.KafkaTopicReader;
import com.ok.member.member.adapter.out.event.FailableMemberMessageSender;
import com.ok.member.member.adapter.out.event.MemberTopics;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.adapter.out.persistence.MemberOutboxJpaRepository;
import com.ok.member.member.application.dto.SignupCommand;
import com.ok.member.member.application.port.in.MemberOutboxRelayUseCase;
import com.ok.member.member.application.port.in.MemberSignupUseCase;
import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 브로커가 일시적으로 실패해 회원가입 메시지를 못 보내도, 줄이 남아 다음 실행에서 다시 보내는지 확인 (#560)
// - 테스트가 함께 쓰는 Kafka 컨테이너는 멈출 수 없어서, 실제 발송기를 감싼 FailableMemberMessageSender로 다음 한 번만 실패시킨다
class MemberOutboxRetryIntegrationTest extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 100;

    @Autowired
    FailableMemberMessageSender failableMemberMessageSender;

    @Autowired
    MemberSignupUseCase memberSignupUseCase;

    @Autowired
    MemberOutboxRelayUseCase memberOutboxRelayUseCase;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    MemberOutboxJpaRepository memberOutboxRepository;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    private KafkaTopicReader reader;

    @BeforeEach
    void setUp() {
        reader = new KafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), MemberTopics.MEMBER_REGISTERED);
    }

    // outbox 줄이 회원을 외래키로 참조하므로 먼저 지운다. 걸어 둔 실패가 다른 테스트로 넘어가지 않게 지운다
    @AfterEach
    void tearDown() {
        failableMemberMessageSender.reset();
        reader.close();
        memberOutboxRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 브로커가_일시적으로_실패하면_실패를_기록하고_다음_실행에서_다시_보내_메시지가_한_건_나간다() {
        // given: 첫 발송은 브로커 장애로 실패한다
        failableMemberMessageSender.failNextSend(
                new IllegalStateException("회원가입 Kafka 발행에 실패했습니다.", new RuntimeException("브로커 일시 장애")));
        Long memberId = memberSignupUseCase.signup(new SignupCommand(
                "retry01", "password1!", "retry01@example.com", "홍길동", "retry01"));
        int sentWhileFailing = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);
        MemberOutbox failed = memberOutboxRepository.findAll().getFirst();
        assertThat(failed).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sentWhileFailing).isZero();
            softly.assertThat(failed.getStatus()).isEqualTo(MemberOutboxStatus.PENDING);
            softly.assertThat(failed.getAttemptCount()).isEqualTo(1);
            softly.assertThat(failed.getLastError()).contains("브로커 일시 장애");
        });

        // when: 브로커가 복구된 뒤 다음 실행
        int sent = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isEqualTo(1);
        assertThat(reader.readValuesFor(memberId, Duration.ofSeconds(3))).containsExactly("{\"memberId\":" + memberId + "}");
        assertThat(memberOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(MemberOutboxStatus.SENT);
    }
}
