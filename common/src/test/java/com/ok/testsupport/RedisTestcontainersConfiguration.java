package com.ok.testsupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 Redis 컨테이너를 JVM 전체에서 하나만 띄워 쓰는 테스트 컨텍스트가 공유하게 한다 (#319).
 * <p>{@link TestcontainersConfiguration}과 같은 방식으로 컨테이너가 아니라 접속 정보({@link DataRedisConnectionDetails})만 빈으로 등록한다.
 * 컨테이너를 빈으로 두면 컨텍스트마다 컨테이너가 새로 뜨거나, 컨텍스트가 닫힐 때 다른 컨텍스트의 Redis까지 멈춘다 (#151).
 * <p>기본 통합 테스트({@link AbstractIntegrationTest})에는 넣지 않는다. Redis를 쓰는 테스트만 {@code @Import}해서, 쓰지 않는 테스트 JVM이 컨테이너를 띄우는 시간을 아낀다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> redis;

    @Bean
    DataRedisConnectionDetails redisConnectionDetails(@Value("${REDIS_VERSION:8.10}") String redisVersion) {
        GenericContainer<?> container = startRedis(redisVersion);
        DataRedisConnectionDetails.Standalone standalone =
                DataRedisConnectionDetails.Standalone.of(container.getHost(), container.getMappedPort(REDIS_PORT));
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return standalone;
            }
        };
    }

    private static synchronized GenericContainer<?> startRedis(String redisVersion) {
        if (redis == null) {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:" + redisVersion + "-alpine"))
                    .withExposedPorts(REDIS_PORT);
            container.start();
            redis = container;
        }
        return redis;
    }

}
