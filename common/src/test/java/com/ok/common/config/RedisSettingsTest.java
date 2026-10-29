package com.ok.common.config;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.core.env.Environment;

/**
 * 모든 앱이 가져다 쓰는 common-redis.yaml의 값이 적용되는지 검증한다. 연결 자체는 RedisConnectionTest가 확인한다.
 * Redis 연결은 처음 쓸 때 맺으므로 컨테이너 없이 설정 값만 확인한다.
 */
// 앱이 import하는 것과 같은 방식으로 읽는다. Redis 리포지터리를 끄는 값도 이 파일에 있다
@DataRedisTest(properties = "spring.config.import=classpath:common-redis.yaml")
class RedisSettingsTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    @Autowired
    DataRedisProperties properties;

    @Autowired
    Environment environment;

    // 값이 빠지거나 바뀌면 Redis가 멈췄을 때 요청 스레드가 오래 묶이거나, Redis 상태가 앱 헬스체크를 정하게 된다
    @Test
    void common_redis_yaml의_설정이_적용된다() {
        // when
        Duration connectTimeout = properties.getConnectTimeout();
        Duration timeout = properties.getTimeout();
        Boolean repositoriesEnabled = environment.getProperty("spring.data.redis.repositories.enabled", Boolean.class);
        Boolean healthEnabled = environment.getProperty("management.health.redis.enabled", Boolean.class);

        // then
        assertSoftly(softly -> {
            softly.assertThat(connectTimeout).isEqualTo(Duration.ofSeconds(2));
            softly.assertThat(timeout).isEqualTo(Duration.ofSeconds(1));
            softly.assertThat(repositoriesEnabled).isFalse();
            softly.assertThat(healthEnabled).isFalse();
        });
    }

}
