package com.ok.member.auth.service;

import com.ok.member.auth.config.JwtProperties;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);

    @Mock
    RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(
                "paldo-gotgan",
                "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtbXVzdC1iZS0zMi1ieXRlcy1vci1tb3Jl",
                Duration.ofMinutes(30),
                REFRESH_EXPIRATION);
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, properties);
    }

    @Test
    void 발급하면_원문을_반환하고_저장소에는_원문이_아닌_해시를_저장한다() {
        // given
        Long memberId = 1L;

        // when
        IssuedRefreshToken sut = refreshTokenService.issue(memberId);

        // then
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getMemberId()).isEqualTo(memberId);
            softly.assertThat(saved.getTokenHash()).isNotEqualTo(sut.value());
            softly.assertThat(saved.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(sut.value()));
            softly.assertThat(saved.getExpiresAt()).isEqualTo(sut.expiresAt());
        });
    }

    @Test
    void 원문은_URL에_안전한_문자로_된_43자_문자열이다() {
        // when
        IssuedRefreshToken sut = refreshTokenService.issue(1L);

        // then
        // 32바이트를 패딩 없는 Base64URL로 인코딩하면 43자다.
        assertThat(sut.value()).matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    void 만료_일시는_발급_시점으로부터_유효기간_뒤다() {
        // given
        LocalDateTime before = LocalDateTime.now();

        // when
        IssuedRefreshToken sut = refreshTokenService.issue(1L);

        // then
        LocalDateTime after = LocalDateTime.now();
        // 만료 일시는 마이크로초 단위로 잘리므로 before 기준보다 최대 1마이크로초 이를 수 있다.
        assertThat(sut.expiresAt())
                .isAfterOrEqualTo(before.plus(REFRESH_EXPIRATION).minusNanos(1_000))
                .isBeforeOrEqualTo(after.plus(REFRESH_EXPIRATION));
    }

    @Test
    void 발급할_때마다_다른_토큰이_만들어진다() {
        // when
        IssuedRefreshToken first = refreshTokenService.issue(1L);
        IssuedRefreshToken second = refreshTokenService.issue(1L);

        // then
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(captor.capture());
        List<RefreshToken> saved = captor.getAllValues();

        assertSoftly(softly -> {
            softly.assertThat(first.value()).isNotEqualTo(second.value());
            softly.assertThat(saved.get(0).getTokenHash()).isNotEqualTo(saved.get(1).getTokenHash());
        });
    }

    @Test
    void 회원_id가_null이면_발급하지_않고_예외가_발생한다() {
        // given
        Long memberId = null;

        // when & then
        assertThatThrownBy(() -> refreshTokenService.issue(memberId))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("memberId는 null일 수 없습니다.");
        verify(refreshTokenRepository, never()).save(any());
    }
}
