package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.service.dto.LoginCommand;
import com.ok.member.auth.service.dto.TokenResult;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class LoginFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final String DUMMY_HASH = "{bcrypt}dummy";
    private static final String ENCODED_PASSWORD = "{bcrypt}encoded";
    private static final Long MEMBER_ID = 1L;

    @Mock
    AuthService authService;

    @Mock
    PasswordEncoder passwordEncoder;

    private LoginFacade loginFacade;

    @BeforeEach
    void setUp() {
        // 생성자에서 아이디가 없을 때 비교할 더미 해시를 만든다
        given(passwordEncoder.encode(anyString())).willReturn(DUMMY_HASH);
        loginFacade = new LoginFacade(authService, passwordEncoder);
    }

    @Test
    void 아이디와_비밀번호가_맞으면_토큰을_발급한다() {
        // given
        Member member = member(MemberStatus.ACTIVE);
        given(authService.findMemberByLoginId("tester01")).willReturn(Optional.of(member));
        given(passwordEncoder.matches("password1!", ENCODED_PASSWORD)).willReturn(true);
        TokenResult issued = new TokenResult("access-token", NOW.plus(Duration.ofMinutes(30)),
                "refresh-token", NOW.plus(Duration.ofDays(14)));
        given(authService.issueTokens(member)).willReturn(issued);

        // when
        TokenResult sut = loginFacade.login(new LoginCommand("tester01", "password1!"));

        // then
        assertThat(sut).isEqualTo(issued);
    }

    @Test
    void 아이디가_없으면_INVALID_CREDENTIALS가_발생하고_응답_시간을_맞추려고_더미_해시와_비교한다() {
        // given
        given(authService.findMemberByLoginId("unknown01")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> loginFacade.login(new LoginCommand("unknown01", "password1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(passwordEncoder).should().matches("password1!", DUMMY_HASH);
        then(authService).should(never()).issueTokens(any());
    }

    @Test
    void 비밀번호가_틀리면_INVALID_CREDENTIALS가_발생하고_토큰을_발급하지_않는다() {
        // given
        given(authService.findMemberByLoginId("tester01")).willReturn(Optional.of(member(MemberStatus.ACTIVE)));
        given(passwordEncoder.matches("wrong-pass1!", ENCODED_PASSWORD)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> loginFacade.login(new LoginCommand("tester01", "wrong-pass1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(authService).should(never()).issueTokens(any());
    }

    @ParameterizedTest
    @MethodSource("로그인할_수_없는_회원_상태")
    void 로그인할_수_없는_상태의_회원은_비밀번호가_맞아도_예외가_발생하고_토큰을_발급하지_않는다(
            MemberStatus status, AuthErrorCode expected) {
        // given
        given(authService.findMemberByLoginId("tester01")).willReturn(Optional.of(member(status)));
        given(passwordEncoder.matches("password1!", ENCODED_PASSWORD)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> loginFacade.login(new LoginCommand("tester01", "password1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        then(authService).should(never()).issueTokens(any());
    }

    static Stream<Arguments> 로그인할_수_없는_회원_상태() {
        return Stream.of(
                Arguments.of(MemberStatus.WITHDRAWN, AuthErrorCode.INVALID_CREDENTIALS),
                Arguments.of(MemberStatus.SUSPENDED, AuthErrorCode.MEMBER_SUSPENDED)
        );
    }

    @Test
    void 탈퇴한_회원이_틀린_비밀번호로_로그인하면_INVALID_CREDENTIALS가_발생한다() {
        // given
        given(authService.findMemberByLoginId("tester01")).willReturn(Optional.of(member(MemberStatus.WITHDRAWN)));
        given(passwordEncoder.matches("wrong-pass1!", ENCODED_PASSWORD)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> loginFacade.login(new LoginCommand("tester01", "wrong-pass1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(authService).should(never()).issueTokens(any());
    }

    private Member member(MemberStatus status) {
        Member member = Member.createUser("tester01", ENCODED_PASSWORD, "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 1, 12, 0));
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        // 정지·탈퇴로 바꾸는 도메인 메서드가 아직 없어 상태를 직접 설정한다
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }

}
