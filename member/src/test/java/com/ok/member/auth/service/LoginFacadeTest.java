package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.service.dto.LoginCommand;
import com.ok.member.auth.service.dto.LoginCredential;
import com.ok.member.auth.service.dto.TokenResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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
    void 아이디와_비밀번호가_맞으면_그_회원에게_토큰을_발급한다() {
        // given
        given(authService.findLoginCredential("tester01"))
                .willReturn(Optional.of(new LoginCredential(MEMBER_ID, ENCODED_PASSWORD)));
        given(passwordEncoder.matches("password1!", ENCODED_PASSWORD)).willReturn(true);
        TokenResult issued = new TokenResult("access-token", NOW.plus(Duration.ofMinutes(30)),
                "refresh-token", NOW.plus(Duration.ofDays(14)));
        given(authService.issueLoginTokens(MEMBER_ID)).willReturn(issued);

        // when
        TokenResult sut = loginFacade.login(new LoginCommand("tester01", "password1!"));

        // then
        assertThat(sut).isEqualTo(issued);
    }

    @Test
    void 아이디가_없으면_INVALID_CREDENTIALS가_발생하고_응답_시간을_맞추려고_더미_해시와_비교한다() {
        // given
        given(authService.findLoginCredential("unknown01")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> loginFacade.login(new LoginCommand("unknown01", "password1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(passwordEncoder).should().matches("password1!", DUMMY_HASH);
        then(authService).should(never()).issueLoginTokens(any());
    }

    @Test
    void 비밀번호가_틀리면_INVALID_CREDENTIALS가_발생하고_토큰을_발급하지_않는다() {
        // given
        given(authService.findLoginCredential("tester01"))
                .willReturn(Optional.of(new LoginCredential(MEMBER_ID, ENCODED_PASSWORD)));
        given(passwordEncoder.matches("wrong-pass1!", ENCODED_PASSWORD)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> loginFacade.login(new LoginCommand("tester01", "wrong-pass1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(authService).should(never()).issueLoginTokens(any());
    }

}
