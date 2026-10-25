package com.ok.member.auth.repository;

import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.service.RefreshTokenService;
import com.ok.member.auth.service.TokenHasher;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * 실제 MySQL(Testcontainers)과 Flyway 마이그레이션으로 Refresh Token 저장을 검증한다.
 * 엔티티와 마이그레이션 SQL이 맞는지(ddl-auto: validate)도 함께 확인된다.
 *
 * <p>refresh_token.member_id는 외래 키가 없어(auth 모듈 분리 예정), 회원을 저장하지 않고 memberId만으로 테스트한다.
 *
 * <p>DB 제약 위반은 전체 메시지 대신 위반한 제약 이름이 메시지에 포함되는지로 검증한다.
 * 전체 메시지에는 SQL과 입력값이 들어가 매번 달라지고, 제약 이름만 있으면 어떤 제약에 걸렸는지 구분된다.
 */
class RefreshTokenIntegrationTest extends AbstractIntegrationTest {

    private static final Long MEMBER_ID = 1L;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    Clock clock;

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
    }

    @Test
    void 발급한_토큰은_원문의_해시로_조회되고_원문으로는_조회되지_않는다() {
        // when
        IssuedRefreshToken issued = refreshTokenService.issue(MEMBER_ID);

        // then
        RefreshToken sut = refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(issued.value()))
                .orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMemberId()).isEqualTo(MEMBER_ID);
            softly.assertThat(sut.getTokenHash()).isNotEqualTo(issued.value());
            softly.assertThat(sut.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(issued.expiresAt(), clock.getZone()));
            softly.assertThat(sut.getCreatedAt()).isNotNull();
            softly.assertThat(refreshTokenRepository.findByTokenHash(issued.value())).isEmpty();
        });
    }

    @Test
    void 같은_회원이_여러_번_발급받으면_토큰이_각각_저장된다() {
        // when
        refreshTokenService.issue(MEMBER_ID);
        refreshTokenService.issue(MEMBER_ID);

        // then
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_해시를_두_번_저장하면_unique_제약에_걸린다() {
        // given
        refreshTokenRepository.saveAndFlush(token(MEMBER_ID, "same"));
        RefreshToken duplicated = token(2L, "same");

        // when & then
        assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_refresh_token_token_hash");
    }

    private RefreshToken token(Long memberId, String rawValue) {
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(14).truncatedTo(ChronoUnit.MICROS);
        return RefreshToken.create(memberId, TokenHasher.sha256Hex(rawValue), expiresAt);
    }
}
