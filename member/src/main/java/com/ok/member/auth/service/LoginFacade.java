package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.service.dto.LoginCommand;
import com.ok.member.auth.service.dto.TokenResult;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인 아이디와 비밀번호로 회원을 인증하고 Access Token과 Refresh Token을 발급한다.
 * bcrypt 비교 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고, DB 작업은 {@link AuthService}의
 * 짧은 트랜잭션에 맡긴다. 그래서 한 요청이 동시에 쥐는 커넥션은 최대 1개다.
 * {@code @Transactional(propagation = NOT_SUPPORTED)}로 대신하면 안 된다. 그 범위 안에서 처음 조회한 커넥션이
 * 메서드가 끝날 때까지 반환되지 않아, 뒤의 트랜잭션과 합쳐 요청 하나가 커넥션 2개를 쥐게 된다.
 * 동시 로그인이 풀 크기에 닿으면 모든 요청이 서로를 기다리며 connectionTimeout까지 멈춘다(#425).
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
        Member member = authenticate(command);
        validateLoginable(member);
        return authService.issueTokens(member);
    }

    private Member authenticate(LoginCommand command) {
        Member member = authService.findMemberByLoginId(command.loginId()).orElse(null);

        // 회원이 없어도 더미 해시로 비교를 한 번 수행한다. 비교를 한 곳에서만 하면 두 경로의 비용이 구조적으로 같아진다.
        String passwordHash = member != null ? member.getPassword() : dummyPasswordHash;
        boolean matches = passwordEncoder.matches(command.password(), passwordHash);

        if (member == null || !matches) {
            throw new RestApiException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        return member;
    }

    private void validateLoginable(Member member) {
        // 탈퇴 회원은 아이디가 익명화될 예정이라 "없는 회원"과 같게 취급해 가입 이력을 드러내지 않는다.
        // 정지 회원은 비밀번호까지 맞은 본인이므로, 왜 로그인이 안 되는지 알 수 있게 별도 코드로 알린다.
        if (member.isWithdrawn()) {
            throw new RestApiException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new RestApiException(AuthErrorCode.MEMBER_SUSPENDED);
        }
    }

}
