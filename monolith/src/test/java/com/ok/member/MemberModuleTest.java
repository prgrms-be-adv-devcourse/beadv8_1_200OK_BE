package com.ok.member;

import com.ok.common.event.MemberRegisteredEvent;
import com.ok.common.event.SellerApprovedEvent;
import com.ok.common.media.MediaUrlResolver;
import com.ok.common.config.JpaConfig;
import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.MemberSignupService;
import com.ok.member.member.service.dto.SignupCommand;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.repository.SellerApplicationRepository;
import com.ok.member.seller.service.SellerApplicationService;
import com.ok.testsupport.TestClockConfiguration;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@ApplicationModuleTest
// auth의 토큰 발급·재발급 서비스가 Clock을 주입받으므로 Clock 빈이 필요하다.
// 운영 ClockConfig(com.ok.common.config)는 다른 모듈이라 모듈 테스트에서 걸러지므로, 테스트용 복사본 TestClockConfiguration을 불러온다.
// (#93에서 Clock 빈을 member → config로 옮기면서 생긴 처리, 자세한 이유는 TestClockConfiguration 참고)
@Import({TestcontainersConfiguration.class, JpaConfig.class, TestClockConfiguration.class})
@TestPropertySource(properties = "app.problem.base-uri=https://api.example.com/problems")
public class MemberModuleTest {

    @Autowired
    MemberSignupService memberSignupService;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    SellerApplicationService sellerApplicationService;

    @Autowired
    SellerApplicationRepository sellerApplicationRepository;

    // 모듈 테스트는 member 밖(common 모듈)의 MediaUrlResolver를 걸러 내, 판매자 프로필 서비스를 만들 수 없다.
    // 이 테스트는 이벤트 발행만 확인하고 프로필 사진 URL 변환은 쓰지 않으므로 가짜로 둔다
    @MockitoBean
    MediaUrlResolver mediaUrlResolver;

    // 판매자 신청이 member를 외래키로 참조하므로 신청을 먼저 지운다
    @AfterEach
    void tearDown() {
        sellerApplicationRepository.deleteAll();
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

    @Test
    void 판매자_신청을_승인하면_신청한_회원과_승인_일시로_SellerApprovedEvent를_발행한다(Scenario scenario) {
        // given: 심사 중인 신청
        Long applicantId = saveMember("seller01", "seller01@example.com");
        Long adminId = saveMember("admin01", "admin01@example.com");
        SellerApplication application = SellerApplication.apply(applicantId, "청송 햇살농원", "김영수",
                "512-81-04273", "제2026-경북청송-0031호", LocalDateTime.of(2026, 10, 18, 14, 30));
        application.startReview(LocalDateTime.of(2026, 10, 19, 9, 0));
        Long applicationId = sellerApplicationRepository.saveAndFlush(application).getId();

        // when & then: wallet 모듈이 이 이벤트로 판매자 지갑을 만든다 (WalletModuleTest가 수신 쪽을 확인)
        scenario.stimulate(() -> sellerApplicationService.approve(adminId, applicationId))
                .andWaitForEventOfType(SellerApprovedEvent.class)
                .toArriveAndVerify(event -> assertSoftly(softly -> {
                    softly.assertThat(event.memberId()).isEqualTo(applicantId);
                    softly.assertThat(event.approvedAt()).isEqualTo(
                            sellerApplicationRepository.findById(applicationId).orElseThrow().getApprovedAt());
                }));
    }

    // reviewer_id가 member를 외래키로 참조한다. 관리자 회원 생성 메서드가 아직 없어 관리자도 일반 회원으로 만든다
    private Long saveMember(String loginId, String email) {
        return memberRepository.saveAndFlush(Member.createUser(loginId, "{bcrypt}encoded", email,
                "김영수", loginId, LocalDateTime.of(2026, 10, 1, 0, 0))).getId();
    }

}
