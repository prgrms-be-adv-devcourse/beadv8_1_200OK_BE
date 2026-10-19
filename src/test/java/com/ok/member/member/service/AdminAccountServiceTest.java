package com.ok.member.member.service;

import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberType;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.dto.AdminAccountCommand;
import com.ok.member.member.service.dto.AdminAccountCreation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    // 2026-10-19 12:00 (Asia/Seoul)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String PASSWORD = "Admin123!";

    @Mock
    MemberRepository memberRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    AdminAccountService adminAccountService;

    private final AdminAccountCommand command = new AdminAccountCommand("admin01", PASSWORD, "admin@example.com");

    // @InjectMocks는 @Mock 필드만 생성자에 넣어 주므로 Clock을 넣어 직접 만든다
    @BeforeEach
    void setUp() {
        adminAccountService = new AdminAccountService(memberRepository, passwordEncoder, CLOCK);
    }

    @Test
    void 같은_아이디의_회원이_없으면_인코딩한_비밀번호로_관리자를_만든다() {
        // given
        given(memberRepository.findByLoginId("admin01")).willReturn(Optional.empty());
        given(passwordEncoder.encode(PASSWORD)).willReturn("{bcrypt}encoded");

        // when
        AdminAccountCreation result = adminAccountService.createAdminIfAbsent(command);

        // then
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        then(memberRepository).should().save(captor.capture());
        Member sut = captor.getValue();

        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(AdminAccountCreation.CREATED);
            softly.assertThat(sut.getType()).isEqualTo(MemberType.ADMIN);
            softly.assertThat(sut.getLoginId()).isEqualTo("admin01");
            softly.assertThat(sut.getPassword()).isEqualTo("{bcrypt}encoded");
            softly.assertThat(sut.getEmail()).isEqualTo("admin@example.com");
            softly.assertThat(sut.getName()).isEqualTo(AdminAccountService.ADMIN_NAME);
            softly.assertThat(sut.getTermsAgreedAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 12, 0));
        });
    }

    @Test
    void 같은_아이디의_관리자가_있으면_만들지_않고_비밀번호도_바꾸지_않는다() {
        // given
        Member admin = Member.createAdmin("admin01", "{bcrypt}original", "admin@example.com",
                "관리자", "관리자", LocalDateTime.of(2026, 10, 1, 0, 0));
        given(memberRepository.findByLoginId("admin01")).willReturn(Optional.of(admin));

        // when
        AdminAccountCreation result = adminAccountService.createAdminIfAbsent(command);

        // then
        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(AdminAccountCreation.ALREADY_EXISTS);
            softly.assertThat(admin.getPassword()).isEqualTo("{bcrypt}original");
        });
        then(passwordEncoder).should(never()).encode(anyString());
        then(memberRepository).should(never()).save(any());
    }

    @Test
    void 같은_아이디를_일반_회원이_쓰고_있으면_관리자를_만들지_않고_그_사실을_알린다() {
        // given
        Member user = Member.createUser("admin01", "{bcrypt}user", "user@example.com",
                "김영수", "admin01", LocalDateTime.of(2026, 10, 1, 0, 0));
        given(memberRepository.findByLoginId("admin01")).willReturn(Optional.of(user));

        // when
        AdminAccountCreation result = adminAccountService.createAdminIfAbsent(command);

        // then: 일반 회원을 관리자로 바꾸지 않는다
        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(AdminAccountCreation.LOGIN_ID_TAKEN);
            softly.assertThat(user.getType()).isEqualTo(MemberType.USER);
        });
        then(memberRepository).should(never()).save(any());
    }

    @Test
    void 이메일을_다른_회원이_쓰고_있으면_설정값_오류로_실패하고_오류_메시지에_비밀번호를_남기지_않는다() {
        // given
        given(memberRepository.findByLoginId("admin01")).willReturn(Optional.empty());
        given(memberRepository.existsByEmail("admin@example.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> adminAccountService.createAdminIfAbsent(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_BOOTSTRAP_EMAIL")
                .hasMessageNotContaining(PASSWORD);
        then(memberRepository).should(never()).save(any());
    }

    @Test
    void 입력의_문자열_표현에_비밀번호를_남기지_않는다() {
        // when
        String sut = command.toString();

        // then
        assertThat(sut).doesNotContain(PASSWORD).contains("password=****");
    }

}
