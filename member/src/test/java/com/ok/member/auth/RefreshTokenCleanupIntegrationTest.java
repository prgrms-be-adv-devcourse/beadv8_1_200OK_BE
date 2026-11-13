package com.ok.member.auth;

import com.ok.member.auth.adapter.out.persistence.RefreshTokenJpaRepository;
import com.ok.member.auth.application.port.in.RefreshTokenCleanupUseCase;
import com.ok.member.auth.application.service.TokenHasher;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료된 Refresh Token 정리를 실제 MySQL(Testcontainers)에서 검증한다.
 * 네이티브 DELETE(ORDER BY, LIMIT)가 MySQL에서 그대로 돌고, 묶음을 나눠 반복해도 만료된 행만 지우는지 확인한다.
 */
class RefreshTokenCleanupIntegrationTest extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 2;

    @Autowired
    RefreshTokenCleanupUseCase refreshTokenCleanup;

    @Autowired
    RefreshTokenJpaRepository refreshTokenRepository;

    // 서비스가 만료를 판단하는 기준 시계. 테스트 데이터도 같은 시계로 만든다
    @Autowired
    Clock clock;

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
    }

    @ParameterizedTest
    @MethodSource("만료된_행_수")
    void 만료된_행만_여러_묶음에_나눠_모두_지운다(int expiredCount) {
        // given
        // 묶음 크기의 배수(마지막 묶음이 0건)와 배수가 아닌 경우(마지막 묶음이 덜 찬다)를 모두 본다
        LocalDateTime now = LocalDateTime.now(clock);
        IntStream.range(0, expiredCount)
                .forEach(i -> save("expired-" + i, now.minusDays(1).minusMinutes(i)));
        save("valid-1", now.plusDays(1));
        save("valid-2", now.plusDays(14));

        // when
        int sut = refreshTokenCleanup.deleteExpired(BATCH_SIZE);

        // then
        assertThat(sut).isEqualTo(expiredCount);
        List<String> remaining = refreshTokenRepository.findAll().stream()
                .map(RefreshToken::getTokenHash)
                .toList();
        assertThat(remaining).containsExactlyInAnyOrder(TokenHasher.sha256Hex("valid-1"), TokenHasher.sha256Hex("valid-2"));
    }

    static Stream<Integer> 만료된_행_수() {
        return Stream.of(5, 4);
    }

    private void save(String rawValue, LocalDateTime expiresAt) {
        refreshTokenRepository.saveAndFlush(RefreshToken.create(1L, TokenHasher.sha256Hex(rawValue), expiresAt));
    }
}
