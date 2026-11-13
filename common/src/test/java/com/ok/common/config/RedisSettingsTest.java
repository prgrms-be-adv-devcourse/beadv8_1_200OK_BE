package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.lettuce.core.ReadFrom;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions.RefreshTrigger;

/**
 * 앱이 가져다 쓰는 common-redis.yaml의 값이 적용되는지 검증한다. 연결 자체는 RedisConnectionTest가 확인한다.
 * Redis 연결은 처음 쓸 때 맺으므로 컨테이너 없이 설정 값만 확인한다.
 */
// 앱이 import하는 것과 같은 방식으로 읽는다. Redis 리포지터리를 끄는 값도 이 파일에 있다
// 클러스터 설정은 REDIS_MODE=cluster 일 때만 더해진다. 기본(단일)은 RedisSingleModeTest가 확인한다 (#530)
@DataRedisTest(properties = {"spring.config.import=classpath:common-redis.yaml", "REDIS_MODE=cluster"})
class RedisSettingsTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    @Autowired
    DataRedisProperties properties;

    @Autowired
    Environment environment;

    @Autowired
    LettuceConnectionFactory connectionFactory;

    // 값이 빠지거나 바뀌면 Redis가 멈췄을 때 요청 스레드가 오래 묶인다
    @Test
    void common_redis_yaml의_설정이_적용된다() {
        // when
        Duration connectTimeout = properties.getConnectTimeout();
        Duration timeout = properties.getTimeout();
        Boolean repositoriesEnabled = environment.getProperty("spring.data.redis.repositories.enabled", Boolean.class);

        // then
        assertSoftly(softly -> {
            softly.assertThat(connectTimeout).isEqualTo(Duration.ofSeconds(2));
            softly.assertThat(timeout).isEqualTo(Duration.ofSeconds(1));
            softly.assertThat(repositoriesEnabled).isFalse();
        });
    }

    // REDIS_CLUSTER_NODES가 없어도 로컬 compose 노드 6대로 접속해야, 호스트에서 실행한 앱이 .env 없이 뜬다
    @Test
    void 노드_목록이_없으면_로컬_compose_노드_6대로_접속한다() {
        // when
        List<String> nodes = properties.getCluster().getNodes();
        Integer maxRedirects = properties.getCluster().getMaxRedirects();

        // then
        assertSoftly(softly -> {
            softly.assertThat(nodes).containsExactly(
                    "redis-1.localhost:7001", "redis-2.localhost:7002", "redis-3.localhost:7003",
                    "redis-4.localhost:7004", "redis-5.localhost:7005", "redis-6.localhost:7006");
            softly.assertThat(maxRedirects).isEqualTo(3);
        });
    }

    // 장애 조치로 마스터가 바뀐 뒤 앱이 새 마스터를 못 찾으면 연결이 복구되지 않는다
    // adaptive 갱신은 속성이 아니라 Lettuce 기본값으로 켜져 있어서, 기본값이 바뀌면 이 테스트가 알려 준다
    @Test
    void 토폴로지를_리다이렉트_즉시_그리고_주기적으로_다시_읽는다() {
        // given
        ClusterClientOptions clientOptions = (ClusterClientOptions) connectionFactory.getClientConfiguration()
                .getClientOptions().orElseThrow();

        // when
        ClusterTopologyRefreshOptions actual = clientOptions.getTopologyRefreshOptions();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.getAdaptiveRefreshTriggers()).contains(
                    RefreshTrigger.MOVED_REDIRECT, RefreshTrigger.PERSISTENT_RECONNECTS, RefreshTrigger.UNCOVERED_SLOT);
            softly.assertThat(actual.isPeriodicRefreshEnabled()).isTrue();
            softly.assertThat(actual.getRefreshPeriod()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void 읽기는_복제본을_우선한다() {
        // when
        ReadFrom actual = connectionFactory.getClientConfiguration().getReadFrom().orElseThrow();

        // then
        assertThat(actual).isEqualTo(ReadFrom.REPLICA_PREFERRED);
    }

}
