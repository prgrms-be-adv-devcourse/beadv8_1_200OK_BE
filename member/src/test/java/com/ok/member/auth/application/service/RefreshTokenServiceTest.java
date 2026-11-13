package com.ok.member.auth.application.service;

import com.ok.common.config.JwtProperties;
import com.ok.common.exception.RestApiException;
import com.ok.member.auth.application.dto.TokenReissueCommand;
import com.ok.member.auth.application.port.out.RefreshTokenRepository;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.domain.RotatedRefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
                null,
                List.of(new JwtProperties.VerificationKey("rs256-v1", new ClassPathResource("jwt/test-public.pem"))),
                null,
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
        // 32바이트를 패딩 없는 Base64URL로 인코딩하면 43자다. 요청 검증이 이 길이 상수를 쓰므로 계산식이 실제 인코딩 결과와 맞는지 확인한다
        assertThat(sut.value())
                .matches("^[A-Za-z0-9_-]+$")
                .hasSize(TokenReissueCommand.REFRESH_TOKEN_LENGTH);
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
    void 회전하면_같은_기록의_해시를_새_토큰으로_바꾼다() {
        // given
        RefreshToken stored = storedToken(10L, NOW.plusDays(1));
        given(refreshTokenRepository.findIdByTokenHash(TokenHasher.sha256Hex("raw-token"))).willReturn(Optional.of(10L));
        given(refreshTokenRepository.findForUpdateById(10L)).willReturn(Optional.of(stored));

        // when
        RotatedRefreshToken sut = refreshTokenService.rotate("raw-token");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.memberId()).isEqualTo(1L);
            softly.assertThat(stored.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(sut.issued().value()));
            // 2026-10-12 14:00:00.123456789 → 마이크로초 단위로 잘려 .123456
            softly.assertThat(stored.getRotatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 14, 0, 0, 123_456_000));
            // 회전할 때마다 유효기간이 처음부터 다시 시작된다
            softly.assertThat(stored.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 14, 0, 0, 123_456_000));
            softly.assertThat(sut.issued().expiresAt()).isEqualTo(stored.getExpiresAt().atZone(ZONE).toInstant());
        });
        // 행을 새로 만들지 않는다
        then(refreshTokenRepository).should(never()).save(any());
    }

    @Test
    void 저장된_기록이_없는_토큰이면_INVALID_REFRESH_TOKEN이_발생한다() {
        // given
        // 처음부터 없던 토큰과 이미 회전된 토큰은 행에 해시가 없어 같은 경로로 거절된다
        given(refreshTokenRepository.findIdByTokenHash(any())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> refreshTokenService.rotate("unknown-token"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        // 없는 해시로는 잠금 조회를 하지 않는다 (인덱스 구간 잠금 방지)
        then(refreshTokenRepository).should(never()).findForUpdateById(any());
    }

    @Test
    void 잠그기_전에_다른_요청이_먼저_회전했으면_INVALID_REFRESH_TOKEN이_발생하고_다시_회전하지_않는다() {
        // given
        // 잠금 조회는 커밋된 최신 값을 읽으므로, 먼저 회전한 요청이 바꾼 해시가 보인다
        RefreshToken stored = storedToken(10L, NOW.plusDays(1));
        stored.rotate("hash-rotated-by-other-request", NOW, NOW.plusDays(14));
        given(refreshTokenRepository.findIdByTokenHash(any())).willReturn(Optional.of(10L));
        given(refreshTokenRepository.findForUpdateById(10L)).willReturn(Optional.of(stored));

        // when & then
        assertThatThrownBy(() -> refreshTokenService.rotate("raw-token"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        assertThat(stored.getTokenHash()).isEqualTo("hash-rotated-by-other-request");
    }

    @Test
    void 만료된_토큰이면_INVALID_REFRESH_TOKEN이_발생하고_회전하지_않는다() {
        // given
        // 만료 시각과 같은 순간부터 만료로 본다
        RefreshToken expired = storedToken(10L, NOW);
        given(refreshTokenRepository.findIdByTokenHash(any())).willReturn(Optional.of(10L));
        given(refreshTokenRepository.findForUpdateById(10L)).willReturn(Optional.of(expired));

        // when & then
        assertThatThrownBy(() -> refreshTokenService.rotate("raw-token"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        assertThat(expired.getTokenHash()).isEqualTo(TokenHasher.sha256Hex("raw-token"));
    }

    // 저장된 토큰 기록을 흉내 낸다. 기본 키로 잠그므로 id를 넣어 둔다
    private RefreshToken storedToken(Long id, LocalDateTime expiresAt) {
        RefreshToken token = RefreshToken.create(1L, TokenHasher.sha256Hex("raw-token"), expiresAt);
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

}
