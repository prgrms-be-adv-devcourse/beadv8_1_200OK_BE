package com.ok.member.member;

import com.ok.member.KafkaTopicReader;
import com.ok.member.member.adapter.out.event.MemberTopics;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.adapter.out.persistence.MemberOutboxJpaRepository;
import com.ok.member.member.application.dto.SignupCommand;
import com.ok.member.member.application.port.in.MemberOutboxRelayUseCase;
import com.ok.member.member.application.port.in.MemberSignupUseCase;
import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxEventType;
import com.ok.member.member.domain.MemberOutboxStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 회원가입이 커밋되면 outbox 줄이 남고, 발송 작업이 실제 Kafka 토픽에 memberId를 키로 한 메시지를 한 건 보내는지 확인 (#560)
// - 발송 스케줄러는 테스트에서 꺼져 있어(module-test.yml) 발송 유스케이스를 직접 부른다
// - 다른 테스트의 메시지와 섞이지 않게 토픽을 키(memberId)로 골라 센다
// - 브로커 일시 실패 뒤 다시 보내는 경우는 MemberOutboxRetryIntegrationTest가 본다
class MemberRegisteredKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 100;
    // 다시 보낸 중복까지 세려고 이 시간 동안 계속 읽는다. 발송은 저장 확인까지 기다린 뒤 끝나므로 정상이면 바로 읽힌다
    private static final Duration READ_DURATION = Duration.ofSeconds(3);

    @Autowired
    MemberSignupUseCase memberSignupUseCase;

    @Autowired
    MemberOutboxRelayUseCase memberOutboxRelayUseCase;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    MemberOutboxJpaRepository memberOutboxRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    private KafkaTopicReader reader;

    @BeforeEach
    void setUp() {
        reader = new KafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), MemberTopics.MEMBER_REGISTERED);
    }

    // outbox 줄이 회원을 외래키로 참조하므로 먼저 지운다
    @AfterEach
    void tearDown() {
        reader.close();
        memberOutboxRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 회원가입이_커밋되면_outbox에_보낼_줄이_남는다() {
        // when
        Long memberId = memberSignupUseCase.signup(signupCommand("outbox01", "outbox01@example.com"));

        // then
        List<MemberOutbox> sut = memberOutboxRepository.findAll();
        assertThat(sut).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(sut.getFirst().getEventType()).isEqualTo(MemberOutboxEventType.MEMBER_REGISTERED);
            softly.assertThat(sut.getFirst().getMemberId()).isEqualTo(memberId);
            softly.assertThat(sut.getFirst().getStatus()).isEqualTo(MemberOutboxStatus.PENDING);
        });
    }

    @Test
    void 발송_작업이_돌면_memberId를_키로_한_메시지가_한_건_나가고_줄이_보냄이_된다() {
        // given
        Long memberId = memberSignupUseCase.signup(signupCommand("outbox02", "outbox02@example.com"));

        // when
        int sent = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 받는 쪽(장바구니)과 약속한 JSON 그대로
        assertThat(sent).isEqualTo(1);
        assertThat(reader.readValuesFor(memberId, READ_DURATION)).containsExactly("{\"memberId\":" + memberId + "}");
        MemberOutbox sut = memberOutboxRepository.findAll().getFirst();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(MemberOutboxStatus.SENT);
            softly.assertThat(sut.getSentAt()).isNotNull();
        });
    }

    @Test
    void 보낸_줄은_다음_실행에서_다시_보내지_않는다() {
        // given
        Long memberId = memberSignupUseCase.signup(signupCommand("outbox03", "outbox03@example.com"));
        memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // when
        int sent = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isZero();
        assertThat(reader.readValuesFor(memberId, READ_DURATION)).hasSize(1);
    }

    @Test
    void 회원가입_트랜잭션이_롤백되면_outbox_줄이_남지_않아_메시지가_나가지_않는다() {
        // given: 가입을 바깥 트랜잭션 안에서 부르고 끝에 롤백한다 (가입은 바깥 트랜잭션에 합류한다)
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long memberId = transaction.execute(status -> {
            Long id = memberSignupUseCase.signup(signupCommand("outbox04", "outbox04@example.com"));
            status.setRollbackOnly();
            return id;
        });

        // when
        int sent = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isZero();
        assertThat(memberOutboxRepository.findAll()).isEmpty();
        assertThat(reader.readValuesFor(memberId, READ_DURATION)).isEmpty();
    }

    private static SignupCommand signupCommand(String loginId, String email) {
        return new SignupCommand(loginId, "password1!", email, "홍길동", loginId);
    }
}
