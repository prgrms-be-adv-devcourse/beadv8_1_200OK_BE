package com.ok.common.persistence.redis;

import com.ok.common.security.TokenVersionStore;
import com.ok.testsupport.RedisTestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 앱과 같이 Redis 리포지터리를 끈다. common에는 @SpringBootApplication이 없어 켜 두면 리포지터리가 스캔할 패키지를 찾지 못한다
@DataRedisTest(properties = "spring.data.redis.repositories.enabled=false")
@Import({RedisTestcontainersConfiguration.class, RedisTokenVersionStore.class})
class RedisTokenVersionStoreTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    private static final long MEMBER_ID = 987_654_321L;
    private static final String KEY = "member:token-version:v1:" + MEMBER_ID;

    @Autowired
    TokenVersionStore sut;

    @Autowired
    StringRedisTemplate redisTemplate;

    private LettuceConnectionFactory brokenConnectionFactory;

    // 컨테이너를 JVM 안의 다른 Redis 테스트와 공유하므로, 이 테스트가 넣은 키를 남기지 않는다
    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
        if (brokenConnectionFactory != null) {
            brokenConnectionFactory.destroy();
        }
    }

    @Test
    void 저장한_최소_버전을_읽는다() {
        // given
        sut.saveMinVersion(MEMBER_ID, 3L, Duration.ofMinutes(31));

        // when
        OptionalLong actual = sut.findMinVersion(MEMBER_ID);

        // then
        assertThat(actual).hasValue(3L);
    }

    // 다른 서비스가 같은 키를 읽게 되는 계약이라, 키 이름과 값 형식(숫자 문자열)을 그대로 확인한다
    @Test
    void 버전을_붙인_키에_숫자_문자열로_저장하고_TTL을_건다() {
        // when
        sut.saveMinVersion(MEMBER_ID, 3L, Duration.ofMinutes(31));

        // then
        assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("3");
        assertThat(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).isBetween(1L, 31L * 60);
    }

    @Test
    void 기록이_없으면_비어_있다() {
        // when
        OptionalLong actual = sut.findMinVersion(MEMBER_ID);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 더_높은_버전을_저장하면_바꾸고_TTL도_새로_정한다() {
        // given
        sut.saveMinVersion(MEMBER_ID, 1L, Duration.ofSeconds(30));

        // when
        sut.saveMinVersion(MEMBER_ID, 2L, Duration.ofMinutes(31));

        // then
        assertThat(sut.findMinVersion(MEMBER_ID)).hasValue(2L);
        assertThat(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).isGreaterThan(30L * 60);
    }

    // 앞선 비밀번호 변경의 기록이나 그 전에 DB를 읽은 다시 채우기가 늦게 도착해도 최소 버전이 내려가면 안 된다
    @Test
    void 더_낮은_버전이_늦게_도착하면_무시한다() {
        // given
        sut.saveMinVersion(MEMBER_ID, 2L, Duration.ofMinutes(31));

        // when
        sut.saveMinVersion(MEMBER_ID, 1L, Duration.ofMinutes(31));

        // then
        assertThat(sut.findMinVersion(MEMBER_ID)).hasValue(2L);
    }

    // 다시 채우기가 같은 버전을 주기적으로 다시 쓴다. 회원 쪽이 주는 TTL은 같은 만료 시각까지 남은 시간이라 늘리거나 줄일 일이 없다
    @Test
    void 같은_버전을_다시_저장하면_값과_TTL을_그대로_둔다() {
        // given
        sut.saveMinVersion(MEMBER_ID, 2L, Duration.ofMinutes(31));

        // when
        sut.saveMinVersion(MEMBER_ID, 2L, Duration.ofSeconds(30));

        // then
        assertThat(sut.findMinVersion(MEMBER_ID)).hasValue(2L);
        assertThat(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).isGreaterThan(30L * 60);
    }

    @Test
    void 지금_값이_숫자가_아니면_새_값으로_덮어쓴다() {
        // given
        redisTemplate.opsForValue().set(KEY, "not-a-number", Duration.ofMinutes(1));

        // when
        sut.saveMinVersion(MEMBER_ID, 1L, Duration.ofMinutes(31));

        // then
        assertThat(sut.findMinVersion(MEMBER_ID)).hasValue(1L);
    }

    // 쓰는 쪽의 버그는 저장소 장애가 아니므로 예외로 올려 검사를 멈추게 하지 않는다
    @Test
    void 숫자가_아닌_값이_있으면_기록이_없는_것으로_본다() {
        // given
        redisTemplate.opsForValue().set(KEY, "not-a-number", Duration.ofMinutes(1));

        // when
        OptionalLong actual = sut.findMinVersion(MEMBER_ID);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void TTL이_1밀리초_미만이면_저장하지_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.saveMinVersion(MEMBER_ID, 1L, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TTL은 1밀리초 이상이어야 합니다.");
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }

    // 검증기가 "기록 없음"과 "장애"를 구분해야 하므로 CacheStore와 달리 장애를 삼키지 않는다
    @Test
    void Redis에_접근할_수_없으면_조회와_저장_모두_예외를_그대로_올린다() {
        // given
        TokenVersionStore brokenSut = storeWithBrokenRedis();

        // when & then
        assertThatThrownBy(() -> brokenSut.findMinVersion(MEMBER_ID)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> brokenSut.saveMinVersion(MEMBER_ID, 1L, Duration.ofMinutes(31)))
                .isInstanceOf(DataAccessException.class);
    }

    // 앱 종료 중 남은 요청이 이미 멈춘 연결 팩토리를 쓰는 상황. 검증기는 DataAccessException만 장애로 보므로
    // 다른 예외가 나가면 장애 시 통과가 적용되지 않고 인증 요청이 500이 된다
    @Test
    void 연결_팩토리가_멈춘_뒤에도_조회와_저장_모두_DataAccessException으로_올린다() {
        // given
        TokenVersionStore stoppedSut = storeWithStoppedConnectionFactory();

        // when & then
        assertThatThrownBy(() -> stoppedSut.findMinVersion(MEMBER_ID)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> stoppedSut.saveMinVersion(MEMBER_ID, 1L, Duration.ofMinutes(31)))
                .isInstanceOf(DataAccessException.class);
    }

    private TokenVersionStore storeWithStoppedConnectionFactory() {
        brokenConnectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        brokenConnectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(brokenConnectionFactory);
        brokenConnectionFactory.stop();
        return new RedisTokenVersionStore(template);
    }

    // 접속할 수 없는 포트로 향하는 저장소로 Redis 장애를 흉내 낸다. 명령 제한 시간을 짧게 둬 테스트가 오래 걸리지 않게 한다
    private TokenVersionStore storeWithBrokenRedis() {
        brokenConnectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        brokenConnectionFactory.afterPropertiesSet();
        return new RedisTokenVersionStore(new StringRedisTemplate(brokenConnectionFactory));
    }

}
