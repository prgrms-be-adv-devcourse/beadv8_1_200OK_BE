package com.ok.member;

import com.ok.common.event.MemberRegisteredEvent;
import com.ok.config.JpaConfig;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.MemberSignupService;
import com.ok.member.member.service.dto.SignupCommand;
import com.ok.testsupport.TestClockConfiguration;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
// auth의 토큰 발급·재발급 서비스가 Clock을 주입받으므로 Clock 빈이 필요하다.
// 운영 ClockConfig(com.ok.config)는 다른 모듈이라 모듈 테스트에서 걸러지므로, 테스트용 복사본 TestClockConfiguration을 불러온다.
// (#93에서 Clock 빈을 member → config로 옮기면서 생긴 처리, 자세한 이유는 TestClockConfiguration 참고)
@Import({TestcontainersConfiguration.class, JpaConfig.class, TestClockConfiguration.class})
@TestPropertySource(properties = "app.problem.base-uri=https://api.example.com/problems")
public class MemberModuleTest {

    @Autowired
    MemberSignupService memberSignupService;

    @Autowired
    MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        memberRepository.deleteAll();
    }

    @Test
    void 회원가입이_완료되면_MemberRegisteredEvent를_발행한다(Scenario scenario) {
        // given
        SignupCommand command = new SignupCommand(
                "tester01", "password1!", "tester@example.com",
                "홍길동", "길동이"
        );

        // when & then
        scenario.stimulate(() -> memberSignupService.signup(command))
                .andWaitForEventOfType(MemberRegisteredEvent.class)
                .toArriveAndVerify(event -> assertThat(event.memberId()).isNotNull());
    }

}
