package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ok.testsupport.RedisTestcontainersConfiguration;

/**
 * 스타터와 자동 설정, Lettuce가 실제 Redis와 연결되는지 검증한다. Redis를 쓰는 후속 테스트가 같은 방식으로 {@code @Import}하면 된다.
 */
// 앱과 같이 Redis 리포지터리를 끈다. common에는 @SpringBootApplication이 없어 켜 두면 리포지터리가 스캔할 패키지를 찾지 못한다
@DataRedisTest(properties = "spring.data.redis.repositories.enabled=false")
@Import(RedisTestcontainersConfiguration.class)
class RedisConnectionTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    @Autowired
    StringRedisTemplate sut;

    @Test
    void Redis에_PING을_보내면_PONG을_돌려준다() {
        // when
        String actual = sut.execute((RedisCallback<String>) connection -> connection.ping());

        // then
        assertThat(actual).isEqualTo("PONG");
    }

    @Test
    void 저장한_값을_그대로_조회한다() {
        // given
        sut.opsForValue().set("redis-connection-test", "ok");

        // when
        String actual = sut.opsForValue().get("redis-connection-test");

        // then
        assertThat(actual).isEqualTo("ok");
    }

}
