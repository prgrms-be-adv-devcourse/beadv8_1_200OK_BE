package com.ok.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ok.common.security.TokenClaims;
import com.ok.common.security.TokenVersionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class TokenVersionValidatorTest {

    private static final long MEMBER_ID = 1L;

    private final FakeTokenVersionStore store = new FakeTokenVersionStore();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TokenVersionValidator.class);

    private TokenVersionValidator validator;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        validator = new TokenVersionValidator(store, clock, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void 토큰_버전이_최소_버전보다_낮으면_invalid_token으로_거부한다() {
        // given: 버전 0으로 발급된 뒤 비밀번호를 바꿔 최소 버전이 1이 됐다
        store.minVersions.put(MEMBER_ID, 1L);

        // when
        OAuth2TokenValidatorResult sut = validator.validate(token(MEMBER_ID, 0L));

        // then: 만료(token_expired)로 알리면 클라이언트가 소용없는 재발급을 시도하므로 위조와 같은 코드로 재로그인시킨다
        assertThat(sut.getErrors())
                .singleElement()
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
    }

    @Test
    void 토큰_버전이_최소_버전과_같거나_높으면_통과한다() {
        // given
        store.minVersions.put(MEMBER_ID, 1L);

        // when
        OAuth2TokenValidatorResult same = validator.validate(token(MEMBER_ID, 1L));
        OAuth2TokenValidatorResult higher = validator.validate(token(MEMBER_ID, 2L));

        // then
        assertSoftly(softly -> {
            softly.assertThat(same.hasErrors()).isFalse();
            softly.assertThat(higher.hasErrors()).isFalse();
        });
    }

    @Test
    void 최소_버전_기록이_없으면_통과한다() {
        // when
        OAuth2TokenValidatorResult sut = validator.validate(token(MEMBER_ID, 0L));

        // then
        assertThat(sut.hasErrors()).isFalse();
    }

    @Test
    void ver_클레임이_없는_토큰은_버전_0으로_본다() {
        // given: 배포 전에 발급된 토큰은 ver가 없다. 최소 버전이 없으면 통과하고, 1이면 거부되어야 한다
        Jwt legacyToken = Jwt.withTokenValue("token").header("alg", "RS256").subject(String.valueOf(MEMBER_ID)).build();

        // when
        OAuth2TokenValidatorResult beforeChange = validator.validate(legacyToken);
        store.minVersions.put(MEMBER_ID, 1L);
        OAuth2TokenValidatorResult afterChange = validator.validate(legacyToken);

        // then
        assertSoftly(softly -> {
            softly.assertThat(beforeChange.hasErrors()).isFalse();
            softly.assertThat(afterChange.hasErrors()).isTrue();
        });
    }

    @Test
    void ver_클레임이_숫자가_아니면_저장소를_부르지_않고_거부한다() {
        // given
        Jwt token = Jwt.withTokenValue("token").header("alg", "RS256").subject(String.valueOf(MEMBER_ID))
                .claim(TokenClaims.TOKEN_VERSION, "1").build();

        // when
        OAuth2TokenValidatorResult sut = validator.validate(token);

        // then
        assertThat(sut.hasErrors()).isTrue();
        assertThat(store.calls).hasValue(0);
    }

    @Test
    void 회원_식별자가_숫자가_아닌_토큰은_판단하지_않고_저장소도_부르지_않는다() {
        // given: 회원 식별자 검사는 인증 변환 단계가 맡는다
        Jwt token = Jwt.withTokenValue("token").header("alg", "RS256").subject("not-a-number")
                .claim(TokenClaims.TOKEN_VERSION, 0L).build();

        // when
        OAuth2TokenValidatorResult sut = validator.validate(token);

        // then
        assertThat(sut.hasErrors()).isFalse();
        assertThat(store.calls).hasValue(0);
    }

    @Test
    void 저장소에_접근하지_못하면_통과시키고_10초_동안_저장소를_부르지_않는다() {
        // given
        store.failing = true;
        store.minVersions.put(MEMBER_ID, 1L);

        // when: 첫 요청이 장애를 감지한 뒤 10초 안의 요청 100건
        OAuth2TokenValidatorResult first = validator.validate(token(MEMBER_ID, 0L));
        List<OAuth2TokenValidatorResult> during = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            clock.advance(Duration.ofMillis(99));
            during.add(validator.validate(token(MEMBER_ID, 0L)));
        }

        // then: 폐기된 토큰도 통과하지만(장애 시 허용) 저장소는 처음 한 번만 불렸다
        assertSoftly(softly -> {
            softly.assertThat(first.hasErrors()).isFalse();
            softly.assertThat(during).allMatch(result -> !result.hasErrors());
            softly.assertThat(store.calls).hasValue(1);
            softly.assertThat(meterRegistry.counter("token.version.check", "result", "skipped").count()).isEqualTo(100);
        });
    }

    @Test
    void 장애가_계속되는_동안_경고는_장애를_감지할_때_한_번만_남긴다() {
        // given
        store.failing = true;

        // when: 10초마다 시험 요청이 실패하는 상황을 1분 동안 이어 간다
        for (int i = 0; i < 600; i++) {
            validator.validate(token(MEMBER_ID, 0L));
            clock.advance(Duration.ofMillis(100));
        }

        // then
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(1);
    }

    @Test
    void 건너뛰는_시간이_지나면_요청_하나로_시험하고_성공하면_검사를_다시_시작한다() {
        // given
        store.failing = true;
        validator.validate(token(MEMBER_ID, 0L));
        store.failing = false;
        store.minVersions.put(MEMBER_ID, 1L);
        clock.advance(TokenVersionValidator.SKIP_DURATION);

        // when
        OAuth2TokenValidatorResult probe = validator.validate(token(MEMBER_ID, 0L));
        OAuth2TokenValidatorResult next = validator.validate(token(MEMBER_ID, 0L));

        // then: 시험 요청부터 폐기된 토큰을 다시 거부하고, 복구를 info로 한 번 남긴다
        assertSoftly(softly -> {
            softly.assertThat(probe.hasErrors()).isTrue();
            softly.assertThat(next.hasErrors()).isTrue();
            softly.assertThat(store.calls).hasValue(3);
            softly.assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.INFO).hasSize(1);
        });
    }

    @Test
    void 시험_요청이_실패하면_다시_10초_동안_저장소를_부르지_않는다() {
        // given
        store.failing = true;
        validator.validate(token(MEMBER_ID, 0L));
        clock.advance(TokenVersionValidator.SKIP_DURATION);
        validator.validate(token(MEMBER_ID, 0L));

        // when
        clock.advance(TokenVersionValidator.SKIP_DURATION.minusMillis(1));
        validator.validate(token(MEMBER_ID, 0L));

        // then
        assertThat(store.calls).hasValue(2);
    }

    @Test
    void 시험할_차례에_요청이_동시에_와도_저장소는_한_번만_부른다() throws Exception {
        // given: 시험 요청이 저장소 응답을 기다리는 동안 다른 요청들이 들어온다
        store.failing = true;
        validator.validate(token(MEMBER_ID, 0L));
        store.failing = false;
        clock.advance(TokenVersionValidator.SKIP_DURATION);
        store.blockNextCall();
        int requestCount = 20;

        // when
        List<OAuth2TokenValidatorResult> sut = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            Future<OAuth2TokenValidatorResult> probe = executor.submit(() -> validator.validate(token(MEMBER_ID, 0L)));
            assertThat(store.blockedCallEntered.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<OAuth2TokenValidatorResult>> others = new ArrayList<>();
            for (int i = 0; i < requestCount - 1; i++) {
                others.add(executor.submit(() -> validator.validate(token(MEMBER_ID, 0L))));
            }
            for (Future<OAuth2TokenValidatorResult> other : others) {
                sut.add(other.get(5, TimeUnit.SECONDS));
            }
            store.releaseBlockedCall();
            sut.add(probe.get(5, TimeUnit.SECONDS));
        }

        // then: 처음 장애를 감지한 1회 + 시험 1회
        assertThat(store.calls).hasValue(2);
        assertThat(sut).hasSize(requestCount).allMatch(result -> !result.hasErrors());
    }

    private static Jwt token(long memberId, long version) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(String.valueOf(memberId))
                .claim(TokenClaims.TOKEN_VERSION, version)
                .build();
    }

    /**
     * 장애와 응답 지연을 흉내 내는 저장소. 호출 횟수로 브레이커가 저장소를 불렀는지 본다.
     */
    private static class FakeTokenVersionStore implements TokenVersionStore {

        final Map<Long, Long> minVersions = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch blockedCallEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        volatile boolean failing;
        private volatile boolean blockNext;

        void blockNextCall() {
            blockNext = true;
        }

        void releaseBlockedCall() {
            release.countDown();
        }

        @Override
        public OptionalLong findMinVersion(long memberId) {
            calls.incrementAndGet();
            if (blockNext) {
                blockNext = false;
                blockedCallEntered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failing) {
                throw new QueryTimeoutException("Redis 응답 없음");
            }
            Long minVersion = minVersions.get(memberId);
            return minVersion == null ? OptionalLong.empty() : OptionalLong.of(minVersion);
        }

        @Override
        public void saveMinVersion(long memberId, long version, Duration ttl) {
            minVersions.put(memberId, version);
        }

    }

    /**
     * 테스트가 직접 움직이는 시계. 기다리지 않고 건너뛰는 시간을 흘린다.
     */
    private static class MutableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-11-17T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

    }

}
