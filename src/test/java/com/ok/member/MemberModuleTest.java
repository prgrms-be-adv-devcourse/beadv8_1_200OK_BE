package com.ok.member;

import com.ok.config.JpaConfig;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.MemberSignupService;
import com.ok.member.member.service.dto.SignupCommand;
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
@Import({TestcontainersConfiguration.class, JpaConfig.class})
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
                .matching(event -> event.memberRole() == MemberRole.BUYER)
                .toArriveAndVerify(event -> assertThat(event.memberId()).isNotNull());
    }

}
