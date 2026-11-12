package com.ok.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;

import com.ok.PaldoGotganApplication;

/**
 * 앱 설정으로 compose가 띄운 실제 Redis에 붙는지 검증한다 (#530).
 * 떠 있는 컨테이너가 필요해 기본 test에서는 빠지고 {@code :monolith:infraTest}에서만 돈다.
 */
@Tag("infraTest")
@DataRedisTest
@ContextConfiguration(classes = PaldoGotganApplication.class)
class RedisInfraConnectionTest {

    private static final String TEST_KEY = "infra-connection-test";

    // compose의 Redis mem_limit(512m). maxmemory가 이보다 작아야 AOF 재작성 중에도 OOM kill되지 않는다
    private static final long CONTAINER_LIMIT_BYTES = 512L * 1024 * 1024;

    @Autowired
    StringRedisTemplate sut;

    @AfterEach
    void tearDown() {
        sut.delete(TEST_KEY);
    }

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
        sut.opsForValue().set(TEST_KEY, "ok");

        // when
        String actual = sut.opsForValue().get(TEST_KEY);

        // then
        assertThat(actual).isEqualTo("ok");
    }

    // compose의 Redis 설정과 같아야 한다
    @Test
    void AOF가_켜져_있고_메모리가_차면_키를_지우지_않고_쓰기를_거절한다() {
        // when
        String appendonly = configValue("appendonly");
        String appendfsync = configValue("appendfsync");
        String maxmemoryPolicy = configValue("maxmemory-policy");

        // then
        assertSoftly(softly -> {
            softly.assertThat(appendonly).isEqualTo("yes");
            softly.assertThat(appendfsync).isEqualTo("everysec");
            softly.assertThat(maxmemoryPolicy).isEqualTo("noeviction");
        });
    }

    // maxmemory가 0이면 상한이 없다는 뜻이라 컨테이너 한도까지 커져 OOM kill된다
    @Test
    void maxmemory가_컨테이너_한도보다_작게_설정돼_있다() {
        // when
        long actual = Long.parseLong(configValue("maxmemory"));

        // then
        assertThat(actual).isPositive().isLessThan(CONTAINER_LIMIT_BYTES);
    }

    private String configValue(String name) {
        Properties config = sut.execute((RedisCallback<Properties>) connection -> connection.serverCommands().getConfig(name));
        assertThat(config).isNotNull();
        return config.getProperty(name);
    }

}
