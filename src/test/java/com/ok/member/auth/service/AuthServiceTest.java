package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.security.TokenRoles;
import com.ok.member.auth.domain.AccessToken;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.service.dto.LoginCommand;
import com.ok.member.auth.service.dto.LoginResult;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.repository.MemberRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final String DUMMY_HASH = "{bcrypt}dummy";
    private static final String ENCODED_PASSWORD = "{bcrypt}encoded";
    private static final Long MEMBER_ID = 1L;

    @Mock
    MemberRepository memberRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    AccessTokenProvider accessTokenProvider;

    @Mock
    RefreshTokenService refreshTokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // 생성자에서 아이디가 없을 때 비교할 더미 해시를 만든다
        given(passwordEncoder.encode(anyString())).willReturn(DUMMY_HASH);
        authService = new AuthService(memberRepository, passwordEncoder, accessTokenProvider, refreshTokenService);
    }

    @Test
    void 아이디와_비밀번호가_맞으면_Access_Token과_Refresh_Token을_발급한다() {
        // given
        Member member = member(MemberStatus.ACTIVE);
        given(memberRepository.findByLoginId("tester01")).willReturn(Optional.of(member));
        given(passwordEncoder.matches("password1!", ENCODED_PASSWORD)).willReturn(true);

        Instant accessExpiresAt = NOW.plus(Duration.ofMinutes(30));
        Instant refreshExpiresAt = NOW.plus(Duration.ofDays(14));
        given(accessTokenProvider.issue(MEMBER_ID, List.of(TokenRoles.BUYER)))
                .willReturn(new AccessToken("access-token", accessExpiresAt));
        given(refreshTokenService.issue(MEMBER_ID))
                .willReturn(new IssuedRefreshToken("refresh-token", refreshExpiresAt));

        // when
        LoginResult sut = authService.login(new LoginCommand("tester01", "password1!"));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.accessToken()).isEqualTo("access-token");
            softly.assertThat(sut.accessTokenExpiresAt()).isEqualTo(accessExpiresAt);
            softly.assertThat(sut.refreshToken()).isEqualTo("refresh-token");
            softly.assertThat(sut.refreshTokenExpiresAt()).isEqualTo(refreshExpiresAt);
        });
        then(refreshTokenService).should().issue(MEMBER_ID);
    }

    @Test
    void 아이디가_없으면_INVALID_CREDENTIALS가_발생하고_응답_시간을_맞추려고_더미_해시와_비교한다() {
        // given
        given(memberRepository.findByLoginId("unknown01")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.login(new LoginCommand("unknown01", "password1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(passwordEncoder).should().matches("password1!", DUMMY_HASH);
        then(accessTokenProvider).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    @Test
    void 비밀번호가_틀리면_INVALID_CREDENTIALS가_발생하고_토큰을_발급하지_않는다() {
        // given
        given(memberRepository.findByLoginId("tester01")).willReturn(Optional.of(member(MemberStatus.ACTIVE)));
        given(passwordEncoder.matches("wrong-pass1!", ENCODED_PASSWORD)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.login(new LoginCommand("tester01", "wrong-pass1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(accessTokenProvider).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("로그인할_수_없는_회원_상태")
    void 로그인할_수_없는_상태의_회원은_비밀번호가_맞아도_예외가_발생하고_토큰을_발급하지_않는다(
            MemberStatus status, AuthErrorCode expected) {
        // given
        given(memberRepository.findByLoginId("tester01")).willReturn(Optional.of(member(status)));
        given(passwordEncoder.matches("password1!", ENCODED_PASSWORD)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.login(new LoginCommand("tester01", "password1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        then(accessTokenProvider).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    @Test
    void 탈퇴한_회원이_틀린_비밀번호로_로그인하면_INVALID_CREDENTIALS가_발생한다() {
        // given
        given(memberRepository.findByLoginId("tester01")).willReturn(Optional.of(member(MemberStatus.WITHDRAWN)));
        given(passwordEncoder.matches("wrong-pass1!", ENCODED_PASSWORD)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.login(new LoginCommand("tester01", "wrong-pass1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(accessTokenProvider).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    static Stream<Arguments> 로그인할_수_없는_회원_상태() {
        return Stream.of(
                Arguments.of(MemberStatus.WITHDRAWN, AuthErrorCode.INVALID_CREDENTIALS),
                Arguments.of(MemberStatus.SUSPENDED, AuthErrorCode.MEMBER_SUSPENDED)
        );
    }

    private Member member(MemberStatus status) {
        Member member = Member.createUser("tester01", ENCODED_PASSWORD, "tester@example.com",
                "홍길동", "길동이",
                LocalDateTime.of(2026, 10, 1, 12, 0));
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        // 정지·탈퇴로 바꾸는 도메인 메서드가 아직 없어 상태를 직접 설정한다
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }

}
