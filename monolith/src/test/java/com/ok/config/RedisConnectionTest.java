package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;

import com.ok.PaldoGotganApplication;
import com.ok.testsupport.RedisTestcontainersConfiguration;

/**
 * 스타터와 자동 설정, Lettuce가 실제 Redis와 연결되는지 검증한다. Redis를 쓰는 후속 테스트가 같은 방식으로 {@code @Import}하면 된다.
 */
@DataRedisTest
@ContextConfiguration(classes = PaldoGotganApplication.class)
@Import(RedisTestcontainersConfiguration.class)
class RedisConnectionTest {

    @Autowired
    StringRedisTemplate sut;

    @Test
    void 저장한_값을_그대로_조회한다() {
        // given
        sut.opsForValue().set("redis-connection-test", "ok");

        // when
        String actual = sut.opsForValue().get("redis-connection-test");

        // then
        assertThat(actual).isEqualTo("ok");
    }

    @Test
    void 없는_키는_null을_돌려준다() {
        // when
        String actual = sut.opsForValue().get("redis-connection-test-missing");

        // then
        assertThat(actual).isNull();
    }

}
