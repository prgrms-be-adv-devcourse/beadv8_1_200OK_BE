package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.domain.AccessToken;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.service.dto.LoginCommand;
import com.ok.member.auth.service.dto.LoginResult;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.repository.MemberRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 로그인 아이디와 비밀번호로 회원을 인증하고 Access Token과 Refresh Token을 발급한다.
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenProvider accessTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    /**
     * 존재하지 않는 아이디로 로그인할 때 비밀번호 비교에 쓰는 해시.
     * 아이디가 없을 때 bcrypt 비교를 건너뛰면 응답이 눈에 띄게 빨라져, 응답 시간만으로 가입 여부를 알아낼 수 있다.
     * 아이디가 없어도 같은 비용의 비교를 한 번 수행해 응답 시간을 맞춘다.
     */
    private final String dummyPasswordHash;

    public AuthService(MemberRepository memberRepository,
                       PasswordEncoder passwordEncoder,
                       AccessTokenProvider accessTokenProvider,
                       RefreshTokenService refreshTokenService,
                       Clock clock
    ) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenProvider = accessTokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
        // 실제 회원 비밀번호와 같은 인코더로 만들어야 비교 비용(bcrypt 강도)이 같아진다
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 로그인 아이디와 비밀번호를 확인하고 토큰을 발급한다.
     * Refresh Token은 해시로 저장되므로 원문은 반환값으로만 얻을 수 있다.
     *
     * @throws RestApiException 아이디가 없거나 비밀번호가 틀리거나 탈퇴한 회원이면 {@code INVALID_CREDENTIALS},
     *                          정지된 회원이면 {@code MEMBER_SUSPENDED}
     */
    @Transactional
    public LoginResult login(LoginCommand command) {
        Member member = authenticate(command);
        validateLoginable(member);

        AccessToken accessToken = accessTokenProvider.issue(member.getId(), member.getRole());
        IssuedRefreshToken refreshToken = refreshTokenService.issue(member.getId());

        // Refresh Token 만료 일시는 Clock의 시간대로 계산한 LocalDateTime이므로, 같은 시간대로 Instant로 바꾼다
        Instant refreshTokenExpiresAt = refreshToken.expiresAt().atZone(clock.getZone()).toInstant();
        return new LoginResult(accessToken.value(), accessToken.expiresAt(),
                refreshToken.value(), refreshTokenExpiresAt);
    }

    private Member authenticate(LoginCommand command) {
        Optional<Member> member = memberRepository.findByLoginId(command.loginId());
        if (member.isEmpty()) {
            passwordEncoder.matches(command.password(), dummyPasswordHash);
            throw new RestApiException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(command.password(), member.get().getPassword())) {
            throw new RestApiException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        return member.get();
    }

    private void validateLoginable(Member member) {
        // 탈퇴 회원은 아이디가 익명화될 예정이라 "없는 회원"과 같게 취급해 가입 이력을 드러내지 않는다.
        // 정지 회원은 비밀번호까지 맞은 본인이므로, 왜 로그인이 안 되는지 알 수 있게 별도 코드로 알린다.
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new RestApiException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new RestApiException(AuthErrorCode.MEMBER_SUSPENDED);
        }
    }

}
