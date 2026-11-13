package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

/**
 * 클러스터 모드(cluster.nodes)로 실제 Redis에 연결되는지 검증한다. 다른 Redis 테스트는 단일 노드 컨테이너를 쓰므로 이 경로를 타지 않는다.
 * 노드 1대가 슬롯 전체를 맡는 클러스터라 장애 조치는 검증하지 못한다. 그건 docs/DEPLOY.md의 수동 절차가 맡는다.
 */
@DataRedisTest(properties = "spring.data.redis.repositories.enabled=false")
class RedisClusterConnectionTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    private static final String TEST_KEY = "redis-cluster-connection-test";

    private static int port;
    private static GenericContainer<?> redis;

    // 클러스터 노드는 컨테이너 안의 자기 포트를 클라이언트에게 알린다. 매핑된 임의 포트로는 다시 접속할 수 없어,
    // 안팎이 같은 빈 포트를 골라 그 포트로 직접 연다
    @BeforeAll
    static void startRedis() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String version = System.getenv().getOrDefault("REDIS_VERSION", "8.10");
        redis = new GenericContainer<>(DockerImageName.parse("redis:" + version + "-alpine"))
                .withExposedPorts(port)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(port))))
                .withCommand("redis-server", "--port", String.valueOf(port), "--cluster-enabled", "yes",
                        "--cluster-announce-hostname", "localhost", "--cluster-preferred-endpoint-type", "hostname");
        redis.start();
        redis.execInContainer("redis-cli", "-p", String.valueOf(port), "cluster", "addslotsrange", "0", "16383");
        awaitClusterOk();
    }

    private static void awaitClusterOk() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            String info = redis.execInContainer("redis-cli", "-p", String.valueOf(port), "cluster", "info").getStdout();
            if (info.contains("cluster_state:ok")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IOException("클러스터가 10초 안에 cluster_state:ok가 되지 않았다");
    }

    @AfterAll
    static void stopRedis() {
        redis.stop();
    }

    @DynamicPropertySource
    static void clusterNodes(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.cluster.nodes", () -> "localhost:" + port);
    }

    @Autowired
    StringRedisTemplate sut;

    @AfterEach
    void tearDown() {
        sut.delete(TEST_KEY);
    }

    @Test
    void 클러스터에_PING을_보내면_PONG을_돌려준다() {
        // when
        String actual = sut.execute((RedisCallback<String>) connection -> connection.ping());

        // then
        assertThat(actual).isEqualTo("PONG");
    }

    @Test
    void 클러스터에_저장한_값을_그대로_조회한다() {
        // given
        sut.opsForValue().set(TEST_KEY, "ok");

        // when
        String actual = sut.opsForValue().get(TEST_KEY);

        // then
        assertThat(actual).isEqualTo("ok");
    }

}
