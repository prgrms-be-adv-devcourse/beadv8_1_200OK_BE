package com.ok.common.persistence.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.ok.testsupport.RedisTestcontainersConfiguration;

import tools.jackson.databind.json.JsonMapper;

// 앱과 같이 Redis 리포지터리를 끈다. common에는 @SpringBootApplication이 없어 켜 두면 리포지터리가 스캔할 패키지를 찾지 못한다
@DataRedisTest(properties = "spring.data.redis.repositories.enabled=false")
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({RedisTestcontainersConfiguration.class, RedisCacheStore.class})
class RedisCacheStoreTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    record CachedItem(String name, int quantity, BigDecimal price, LocalDateTime cachedAt) {
    }

    private static final String KEY = "common:cache-store-test:item";

    private static final Duration TTL = Duration.ofMinutes(10);

    @Autowired
    CacheStore sut;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JsonMapper jsonMapper;

    private LettuceConnectionFactory brokenConnectionFactory;

    // 컨테이너를 JVM 안의 다른 Redis 테스트와 공유하므로, 이 테스트가 넣은 키를 남기지 않는다
    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
        if (brokenConnectionFactory != null) {
            brokenConnectionFactory.destroy();
        }
    }

    @Test
    void 저장한_값을_같은_타입으로_읽는다() {
        // given
        CachedItem item = new CachedItem("캡", 2, new BigDecimal("1500.50"), LocalDateTime.of(2026, 11, 12, 10, 30, 15));
        sut.put(KEY, item, TTL);

        // when
        Optional<CachedItem> actual = sut.get(KEY, CachedItem.class);

        // then
        assertThat(actual).contains(item);
    }

    @Test
    void 없는_키는_빈_값을_돌려준다() {
        // when
        Optional<CachedItem> actual = sut.get(KEY, CachedItem.class);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 같은_키에_다시_저장하면_값을_덮어쓴다() {
        // given
        sut.put(KEY, new CachedItem("이전", 1, BigDecimal.ONE, LocalDateTime.of(2026, 1, 1, 0, 0)), TTL);
        CachedItem latest = new CachedItem("최신", 3, BigDecimal.TEN, LocalDateTime.of(2026, 2, 2, 0, 0));

        // when
        sut.put(KEY, latest, TTL);

        // then
        assertThat(sut.get(KEY, CachedItem.class)).contains(latest);
    }

    @Test
    void 지운_키는_읽을_수_없다() {
        // given
        sut.put(KEY, new CachedItem("캡", 1, BigDecimal.ONE, LocalDateTime.of(2026, 1, 1, 0, 0)), TTL);

        // when
        sut.evict(KEY);

        // then
        assertThat(sut.get(KEY, CachedItem.class)).isEmpty();
    }

    @Test
    void 없는_키를_지워도_오류가_나지_않는다() {
        // when & then
        sut.evict(KEY);
    }

    @Test
    void 저장할_때_정한_TTL이_적용된다() {
        // when
        sut.put(KEY, new CachedItem("캡", 1, BigDecimal.ONE, LocalDateTime.of(2026, 1, 1, 0, 0)), TTL);

        // then
        Long remainingSeconds = redisTemplate.getExpire(KEY, TimeUnit.SECONDS);
        assertThat(remainingSeconds).isBetween(1L, TTL.toSeconds());
    }

    // 클래스 이름 같은 타입 정보를 싣지 않아야, 클래스를 옮기거나 이름을 바꿔도 저장된 값을 읽을 수 있다
    @Test
    void 저장된_값은_타입_정보가_없는_JSON이다() {
        // given
        CachedItem item = new CachedItem("캡", 2, new BigDecimal("1500.50"), LocalDateTime.of(2026, 11, 12, 10, 30, 15));

        // when
        sut.put(KEY, item, TTL);

        // then
        String stored = redisTemplate.opsForValue().get(KEY);
        assertThat(stored).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(stored).startsWith("{").endsWith("}");
            softly.assertThat(stored).contains("\"name\":\"캡\"", "\"quantity\":2");
            softly.assertThat(stored).doesNotContain("@class", "CachedItem");
        });
    }

    // 배포로 클래스 모양이 바뀌면 이전에 저장한 값을 읽지 못한다. 오류로 두면 TTL이 끝날 때까지 같은 키가 계속 실패한다
    @Test
    void 읽을_수_없는_값은_캐시_미스로_보고_지운다() {
        // given
        redisTemplate.opsForValue().set(KEY, "이건 JSON이 아니다");

        // when
        Optional<CachedItem> actual = sut.get(KEY, CachedItem.class);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEmpty();
            softly.assertThat(redisTemplate.hasKey(KEY)).isFalse();
        });
    }

    @Test
    void 필드_타입이_맞지_않는_JSON은_캐시_미스로_보고_지운다() {
        // given
        redisTemplate.opsForValue().set(KEY, "{\"quantity\":\"숫자가 아니다\"}");

        // when
        Optional<CachedItem> actual = sut.get(KEY, CachedItem.class);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEmpty();
            softly.assertThat(redisTemplate.hasKey(KEY)).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_키")
    void 키가_비어_있으면_거부한다(String key) {
        // when & then
        assertThatThrownBy(() -> sut.put(key, "값", TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("키는 필수입니다.");
        assertThatThrownBy(() -> sut.get(key, String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("키는 필수입니다.");
        assertThatThrownBy(() -> sut.evict(key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("키는 필수입니다.");
    }

    static Stream<String> 비어_있는_키() {
        return Stream.of(null, "", " ");
    }

    @ParameterizedTest
    @MethodSource("쓸_수_없는_TTL")
    void TTL이_1밀리초_미만이면_거부한다(Duration ttl) {
        // when & then
        assertThatThrownBy(() -> sut.put(KEY, "값", ttl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TTL은 1밀리초 이상이어야 합니다.");
    }

    static Stream<Duration> 쓸_수_없는_TTL() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(500_000));
    }

    @Test
    void 저장할_값이_null이면_거부한다() {
        // when & then
        assertThatThrownBy(() -> sut.put(KEY, null, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("저장할 값은 필수입니다.");
    }

    @Test
    void 읽을_타입이_null이면_거부한다() {
        // when & then
        assertThatThrownBy(() -> sut.get(KEY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("읽을 타입은 필수입니다.");
    }

    // 캐시는 없어도 본 흐름이 동작해야 하므로 Redis 장애를 읽기에서는 캐시 미스로 흡수한다
    @Test
    void Redis에_접근할_수_없으면_조회는_캐시_미스로_돌려준다() {
        // given
        CacheStore brokenSut = storeWithBrokenRedis();

        // when
        Optional<CachedItem> actual = brokenSut.get(KEY, CachedItem.class);

        // then
        assertThat(actual).isEmpty();
    }

    // 저장하지 못해도 다음 조회가 캐시 미스가 될 뿐이라 쓰기에서도 흡수한다
    @Test
    void Redis에_접근할_수_없으면_저장은_오류_없이_끝난다() {
        // given
        CacheStore brokenSut = storeWithBrokenRedis();

        // when & then
        brokenSut.put(KEY, new CachedItem("캡", 1, BigDecimal.ONE, LocalDateTime.of(2026, 1, 1, 0, 0)), TTL);
    }

    // 삭제를 삼키면 낡은 값이 TTL이 끝날 때까지 남는 것을 호출한 쪽이 알 수 없어, 삭제만 예외를 그대로 올린다
    @Test
    void Redis에_접근할_수_없으면_삭제는_예외를_그대로_올린다() {
        // given
        CacheStore brokenSut = storeWithBrokenRedis();

        // when & then
        assertThatThrownBy(() -> brokenSut.evict(KEY)).isInstanceOf(DataAccessException.class);
    }

    // 읽지 못한 값을 지우는 것은 캐시 미스로 돌려주기 위한 정리일 뿐이라, 지우지 못해도 조회는 오류 없이 미스가 된다
    @Test
    void 읽을_수_없는_값을_지우지_못해도_캐시_미스로_돌려준다() {
        // given
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(template.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(KEY)).willReturn("이건 JSON이 아니다");
        given(template.delete(KEY)).willThrow(new RedisConnectionFailureException("연결 끊김"));
        CacheStore unremovableSut = new RedisCacheStore(template, jsonMapper);

        // when
        Optional<CachedItem> actual = unremovableSut.get(KEY, CachedItem.class);

        // then
        assertThat(actual).isEmpty();
    }

    // 접속할 수 없는 포트로 향하는 저장소로 Redis 장애를 흉내 낸다. 명령 제한 시간을 짧게 둬 테스트가 오래 걸리지 않게 한다
    private CacheStore storeWithBrokenRedis() {
        brokenConnectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        brokenConnectionFactory.afterPropertiesSet();
        return new RedisCacheStore(new StringRedisTemplate(brokenConnectionFactory), jsonMapper);
    }

}
