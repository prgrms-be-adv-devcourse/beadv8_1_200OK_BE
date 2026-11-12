package com.ok.common.persistence.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ok.testsupport.RedisTestcontainersConfiguration;

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

    // 컨테이너를 JVM 안의 다른 Redis 테스트와 공유하므로, 이 테스트가 넣은 키를 남기지 않는다
    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
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

}
