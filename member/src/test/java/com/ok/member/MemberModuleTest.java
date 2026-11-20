package com.ok.member;

import com.ok.common.config.JpaConfig;
import com.ok.common.config.JwtProperties;
import com.ok.common.media.MediaUrlResolver;
import com.ok.common.security.TokenVersionStore;
import com.ok.member.auth.adapter.in.web.JwksController;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.adapter.out.persistence.MemberOutboxJpaRepository;
import com.ok.member.member.application.dto.SignupCommand;
import com.ok.member.member.application.port.in.MemberSignupUseCase;
import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxEventType;
import com.ok.member.member.domain.MemberOutboxStatus;
import com.ok.testsupport.TestClockConfiguration;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@ApplicationModuleTest
// auth의 토큰 발급·재발급 서비스가 Clock을 주입받으므로 Clock 빈이 필요하다.
// 운영 ClockConfig(com.ok.common.config)는 다른 모듈이라 모듈 테스트에서 걸러지므로, 테스트용 복사본 TestClockConfiguration을 불러온다.
// (#93에서 Clock 빈을 member → config로 옮기면서 생긴 처리, 자세한 이유는 TestClockConfiguration 참고)
@Import({TestcontainersConfiguration.class, JpaConfig.class, TestClockConfiguration.class})
// 모듈 테스트는 Kafka 컨테이너를 켜지 않으므로 outbox 발송 스케줄러(#560)를 끈다. 켜 두면 가입 뒤 없는 브로커를 기다리며 스레드를 붙잡는다.
// 토큰 버전 다시 채우기 스케줄러(#561)도 끈다. 아래에서 가짜로 둔 JwtProperties로는 기록할 시간을 계산할 수 없다
@TestPropertySource(properties = {
        "app.problem.base-uri=https://api.example.com/problems",
        "member.outbox.relay.cron=-",
        "member.token-version.resync.cron=-"
})
public class MemberModuleTest {

    @Autowired
    MemberSignupUseCase memberSignupUseCase;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    MemberOutboxJpaRepository memberOutboxRepository;

    // 모듈 테스트는 member 밖(common 모듈)의 MediaUrlResolver를 걸러 내, 판매자 프로필 서비스를 만들 수 없다.
    // 이 테스트는 가입 때 남는 outbox 줄만 확인하고 프로필 사진 URL 변환은 쓰지 않으므로 가짜로 둔다
    @MockitoBean
    MediaUrlResolver mediaUrlResolver;

    // JwtProperties도 common 모듈의 타입이라 같은 이유로 걸러진다. 토큰 발급(JwtEncoder, JwtAccessTokenIssuer 등)은
    // 이 테스트에서 쓰지 않고 컨텍스트가 뜨는 데만 필요하므로 가짜로 둔다. (JwtEncoder는 진짜 키가 있어야 만들 수 있어 함께 가짜로 둔다)
    @MockitoBean
    JwtProperties jwtProperties;

    // 토큰 최소 버전 저장소(Redis 구현)도 common 모듈에 있어 걸러진다. 이 테스트는 비밀번호를 바꾸지 않으므로 가짜로 둔다
    @MockitoBean
    TokenVersionStore tokenVersionStore;

    @MockitoBean
    JwtEncoder jwtEncoder;

    // JWKS 컨트롤러도 생성 시점에 JwtProperties로 공개키 목록을 만들어, 가짜 JwtProperties로는 만들 수 없다
    @MockitoBean
    JwksController jwksController;

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
