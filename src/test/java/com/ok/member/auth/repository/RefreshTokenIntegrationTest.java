package com.ok.member.auth.repository;

import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.service.RefreshTokenService;
import com.ok.member.auth.service.TokenHasher;
import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * 실제 MySQL(Testcontainers)과 Flyway 마이그레이션으로 Refresh Token 저장을 검증한다.
 * 엔티티와 마이그레이션 SQL이 맞는지(ddl-auto: validate)도 함께 확인된다.
 *
 * <p>refresh_token.member_id에 외래 키가 있으므로, 토큰을 저장하기 전에 회원이 실제로 존재해야 한다.
 *
 * <p>DB 제약 위반은 전체 메시지 대신 위반한 제약 이름이 메시지에 포함되는지로 검증한다.
 * 전체 메시지에는 SQL과 입력값이 들어가 매번 달라지고, 제약 이름만 있으면 어떤 제약에 걸렸는지 구분된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenIntegrationTest {

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    MemberRepository memberRepository;

    private Long memberId;

    @BeforeEach
    void setUp() {
        memberId = saveMember("tester01", "tester@example.com", "길동이").getId();
    }

    @AfterEach
    void tearDown() {
        // 외래 키 때문에 토큰(자식)을 먼저 지우고 회원(부모)을 지운다.
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 발급한_토큰은_원문의_해시로_조회되고_원문으로는_조회되지_않는다() {
        // when
        IssuedRefreshToken issued = refreshTokenService.issue(memberId);

        // then
        RefreshToken sut = refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(issued.value()))
                .orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMemberId()).isEqualTo(memberId);
            softly.assertThat(sut.getTokenHash()).isNotEqualTo(issued.value());
            softly.assertThat(sut.getExpiresAt()).isEqualTo(issued.expiresAt());
            softly.assertThat(sut.getCreatedAt()).isNotNull();
            softly.assertThat(refreshTokenRepository.findByTokenHash(issued.value())).isEmpty();
        });
    }

    @Test
    void 같은_회원이_여러_번_발급받으면_토큰이_각각_저장된다() {
        // when
        refreshTokenService.issue(memberId);
        refreshTokenService.issue(memberId);

        // then
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_해시를_두_번_저장하면_unique_제약에_걸린다() {
        // given
        Long otherMemberId = saveMember("tester02", "other@example.com", "다른닉네임").getId();
        refreshTokenRepository.saveAndFlush(token(memberId, "same"));
        RefreshToken duplicated = token(otherMemberId, "same");

        // when & then
        assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_refresh_token_token_hash");
    }

    @Test
    void 존재하지_않는_회원의_토큰은_외래_키_제약에_걸린다() {
        // given
        RefreshToken orphan = token(memberId + 1_000, "orphan");

        // when & then
        assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_refresh_token_member_id");
    }

    private Member saveMember(String loginId, String email, String nickname) {
        return memberRepository.saveAndFlush(Member.createBuyer(
                loginId, "{bcrypt}encoded", email, "홍길동", nickname, LocalDateTime.now()));
    }

    private RefreshToken token(Long memberId, String rawValue) {
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(14).truncatedTo(ChronoUnit.MICROS);
        return RefreshToken.create(memberId, TokenHasher.sha256Hex(rawValue), expiresAt);
    }
}
