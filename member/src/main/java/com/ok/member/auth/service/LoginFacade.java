package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.service.dto.LoginCommand;
import com.ok.member.auth.service.dto.LoginCredential;
import com.ok.member.auth.service.dto.TokenResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인 아이디와 비밀번호로 회원을 인증하고 Access Token과 Refresh Token을 발급한다.
 * bcrypt 비교 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고, DB 작업은 {@link AuthService}의 짧은 트랜잭션에 맡긴다.
 * 그래서 이 클래스에 {@code @Transactional}을 붙이지 않고, 리포지토리를 직접 부르지 않는다.
 * 배경과 어겼을 때의 영향은 {@code docs/decisions/member/425-login-connection-deadlock.md}에 있다.
 */
@Component
public class LoginFacade {

    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 존재하지 않는 아이디로 로그인할 때 비밀번호 비교에 쓰는 해시.
     * 응답 시간에서 가장 큰 차이를 만드는 bcrypt 비교(수십~수백 ms)를 아이디 존재 여부와 관계없이 한 번씩 수행해,
     * 응답 시간으로 가입 여부를 짐작하기 어렵게 한다.
     * DB 조회 시간 등 작은 차이는 남으므로 완전한 방어가 아니라 차이를 줄이는 근사적인 대응이다.
     */
    private final String dummyPasswordHash;

    public LoginFacade(AuthService authService, PasswordEncoder passwordEncoder) {
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        // 회원가입과 같은 인코더(DelegatingPasswordEncoder, 기본 bcrypt 강도 10)로 만들어야 비교 비용이 같아진다.
        // 인코더 알고리즘이나 강도를 바꾸면 기존 회원 해시와 비용이 달라지므로 이 부분도 함께 점검한다.
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 로그인 아이디와 비밀번호를 확인하고 토큰을 발급한다.
     * Refresh Token은 해시로 저장되므로 원문은 반환값으로만 얻을 수 있다.
     *
     * @throws RestApiException 아이디가 없거나 비밀번호가 틀리거나 탈퇴한 회원이면 {@code INVALID_CREDENTIALS},
     *                          정지된 회원이면 {@code MEMBER_SUSPENDED}
     */
    public TokenResult login(LoginCommand command) {
        LoginCredential credential = authenticate(command);
        // 탈퇴·정지 확인은 발급 트랜잭션 안에서 최신 상태로 한다. 비밀번호가 틀리면 여기까지 오지 않으므로
        // 틀린 비밀번호에는 회원 상태와 관계없이 INVALID_CREDENTIALS가 나간다
        return authService.issueLoginTokens(credential);
    }

    private LoginCredential authenticate(LoginCommand command) {
        LoginCredential credential = authService.findLoginCredential(command.loginId()).orElse(null);

        // 회원이 없어도 더미 해시로 비교를 한 번 수행한다. 비교를 한 곳에서만 하면 두 경로의 비용이 구조적으로 같아진다.
        String passwordHash = credential != null ? credential.passwordHash() : dummyPasswordHash;
        boolean matches = passwordEncoder.matches(command.password(), passwordHash);

        if (credential == null || !matches) {
            throw new RestApiException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        return credential;
    }

}
