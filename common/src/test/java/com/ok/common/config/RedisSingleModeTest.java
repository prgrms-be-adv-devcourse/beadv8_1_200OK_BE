package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;

/**
 * REDIS_MODE를 정하지 않으면 common-redis.yaml이 단일 Redis로 접속하게 하는지 검증한다 (#530).
 * 클러스터 속성이 하나라도 섞이면 Boot가 클러스터로 접속해 단일 Redis에 붙지 못한다.
 */
@DataRedisTest(properties = "spring.config.import=classpath:common-redis.yaml")
class RedisSingleModeTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    @Autowired
    DataRedisProperties sut;

    @Test
    void REDIS_MODE가_없으면_단일_Redis_주소로_접속한다() {
        // when
        DataRedisProperties.Cluster cluster = sut.getCluster();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getHost()).isEqualTo("localhost");
            softly.assertThat(sut.getPort()).isEqualTo(6379);
            softly.assertThat(cluster).isNull();
        });
    }

}
