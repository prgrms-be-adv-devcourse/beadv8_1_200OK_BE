package com.ok.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.test.context.ContextConfiguration;

import com.ok.PaldoGotganApplication;

/**
 * monolith가 application.yaml의 spring.config.import로 common-redis.yaml을 가져오는지 검증한다.
 * 설정 값 자체는 common의 RedisSettingsTest가 확인한다.
 */
@DataRedisTest(properties = "REDIS_MODE=cluster")
@ContextConfiguration(classes = PaldoGotganApplication.class)
class RedisSettingsTest {

    @Autowired
    DataRedisProperties sut;

    // import 줄이 빠지면 Redis 타임아웃이 기본값으로 돌아가 요청 스레드가 오래 묶일 수 있다
    @Test
    void common의_Redis_설정을_가져온다() {
        // when
        Duration actual = sut.getTimeout();

        // then
        assertThat(actual).isEqualTo(Duration.ofSeconds(1));
    }

    // import 줄이 빠지면 앱이 단일 Redis(localhost:6379) 설정으로 돌아가, 클러스터에 접속하지 못한다
    @Test
    void common의_클러스터_접속_설정을_가져온다() {
        // when
        DataRedisProperties.Cluster actual = sut.getCluster();

        // then
        assertThat(actual).isNotNull();
        assertThat(actual.getNodes()).hasSize(6);
    }

}
