package com.ok.member;

import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.adapter.out.persistence.MemberOutboxJpaRepository;
import com.ok.member.member.application.dto.SignupCommand;
import com.ok.member.member.application.port.in.MemberSignupUseCase;
import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxEventType;
import com.ok.member.member.domain.MemberOutboxStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// outbox 발송 스케줄러(#560)를 꺼서 가입 뒤 남은 줄이 PENDING인 채로 확인되게 한다.
// 토큰 버전 다시 채우기 스케줄러(#561)도 이 테스트와 관계가 없어 끈다
@TestPropertySource(properties = {
        "app.problem.base-uri=https://api.example.com/problems",
        "member.outbox.relay.cron=-",
        "member.token-version.resync.cron=-"
})
class MemberSignupOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MemberSignupUseCase memberSignupUseCase;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    MemberOutboxJpaRepository memberOutboxRepository;

    // 가입 때 남는 outbox 줄(#560)이 member를 외래키로 참조하므로 먼저 지운다
    @AfterEach
    void tearDown() {
        memberOutboxRepository.deleteAll();
        memberRepository.deleteAll();
    }

    // 회원가입은 스프링 이벤트 대신 outbox 줄로 다른 모듈에 알린다(#683). 실제 Kafka 발송은 MemberRegisteredKafkaIntegrationTest가 본다
    @Test
    void 회원가입이_완료되면_보낼_회원가입_메시지를_outbox에_남긴다() {
        // given
        SignupCommand command = new SignupCommand(
                "tester01", "password1!", "tester@example.com",
                "홍길동", "길동이"
        );

        // when
        Long memberId = memberSignupUseCase.signup(command);

        // then
        List<MemberOutbox> outboxes = memberOutboxRepository.findAll();
        assertThat(outboxes).hasSize(1);
        MemberOutbox sut = outboxes.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(sut.getEventType()).isEqualTo(MemberOutboxEventType.MEMBER_REGISTERED);
            softly.assertThat(sut.getMemberId()).isEqualTo(memberId);
            softly.assertThat(sut.getStatus()).isEqualTo(MemberOutboxStatus.PENDING);
        });
    }

}
