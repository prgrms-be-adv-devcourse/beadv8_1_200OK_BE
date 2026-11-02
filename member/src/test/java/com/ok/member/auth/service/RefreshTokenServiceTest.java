package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.config.JwtProperties;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** 발급 시각을 고정한다. 나노초를 포함해 마이크로초 단위로 잘리는지도 함께 확인한다. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 14, 0, 0, 123_456_789);

    @Mock
    RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(
                "paldo-gotgan",
                "paldo-gotgan-api",
                List.of(new JwtProperties.VerificationKey("rs256-v1", new ClassPathResource("jwt/test-public.pem"))),
                Duration.ofMinutes(30),
                REFRESH_EXPIRATION);
        Clock fixedClock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, properties, fixedClock);
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
            softly.assertThat(saved.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(sut.expiresAt(), ZONE));
        });
    }

    @Test
    void 원문은_URL에_안전한_문자로_된_43자_문자열이다() {
        // when
        IssuedRefreshToken sut = refreshTokenService.issue(1L);

        // then
        // 32바이트를 패딩 없는 Base64URL로 인코딩하면 43자다. 요청 검증이 이 길이 상수를 쓰므로 실제 길이와 맞는지 함께 확인한다
        assertThat(sut.value())
                .matches("^[A-Za-z0-9_-]+$")
                .hasSize(RefreshTokenService.TOKEN_LENGTH);
    }

    @Test
    void 만료_일시는_발급_시각으로부터_유효기간_뒤이고_마이크로초_단위로_잘린다() {
        // when
        IssuedRefreshToken sut = refreshTokenService.issue(1L);

        // then
        // 2026-10-12 14:00:00.123456789 + 14일 → 마이크로초 단위로 잘려 .123456
        Instant expected = LocalDateTime.of(2026, 10, 26, 14, 0, 0, 123_456_000).atZone(ZONE).toInstant();
        assertThat(sut.expiresAt()).isEqualTo(expected);
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

    @Test
    void 사용하면_토큰을_지우고_발급받은_회원_id를_반환한다() {
        // given
        RefreshToken stored = storedToken(10L, NOW.plusDays(1));
        given(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("raw-token"))).willReturn(Optional.of(stored));
        given(refreshTokenRepository.findForUpdateById(10L)).willReturn(Optional.of(stored));

        // when
        Long sut = refreshTokenService.consume("raw-token");

        // then
        assertThat(sut).isEqualTo(1L);
        then(refreshTokenRepository).should().delete(stored);
    }

    @Test
    void 저장된_기록이_없는_토큰이면_INVALID_REFRESH_TOKEN이_발생한다() {
        // given
        // 처음부터 없거나, 이미 사용해 지워진 토큰
        given(refreshTokenRepository.findByTokenHash(any())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> refreshTokenService.consume("unknown-token"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        // 없는 해시로는 잠금 조회를 하지 않는다 (인덱스 구간 잠금 방지)
        then(refreshTokenRepository).should(never()).findForUpdateById(any());
    }

    @Test
    void 잠그기_전에_다른_요청이_먼저_써서_지웠으면_INVALID_REFRESH_TOKEN이_발생한다() {
        // given
        RefreshToken stored = storedToken(10L, NOW.plusDays(1));
        given(refreshTokenRepository.findByTokenHash(any())).willReturn(Optional.of(stored));
        given(refreshTokenRepository.findForUpdateById(10L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> refreshTokenService.consume("raw-token"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        then(refreshTokenRepository).should(never()).delete(any());
    }

    @Test
    void 만료된_토큰이면_INVALID_REFRESH_TOKEN이_발생하고_지우지_않는다() {
        // given
        // 만료 시각과 같은 순간부터 만료로 본다
        RefreshToken expired = storedToken(10L, NOW);
        given(refreshTokenRepository.findByTokenHash(any())).willReturn(Optional.of(expired));
        given(refreshTokenRepository.findForUpdateById(10L)).willReturn(Optional.of(expired));

        // when & then
        assertThatThrownBy(() -> refreshTokenService.consume("raw-token"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        then(refreshTokenRepository).should(never()).delete(any());
    }

    // 저장된 토큰 기록을 흉내 낸다. 기본 키로 잠그므로 id를 넣어 둔다
    private RefreshToken storedToken(Long id, LocalDateTime expiresAt) {
        RefreshToken token = RefreshToken.create(1L, TokenHasher.sha256Hex("raw-token"), expiresAt);
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

}
