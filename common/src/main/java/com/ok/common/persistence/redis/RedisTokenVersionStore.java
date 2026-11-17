package com.ok.common.persistence.redis;

import com.ok.common.security.TokenVersionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * {@link TokenVersionStore}를 Redis로 구현한다.
 * 값은 JSON이 아니라 숫자 문자열 하나로 둔다. 다른 서비스가 같은 키를 읽게 되면 직렬화 설정과 상관없이 읽을 수 있어야 해서다.
 * 저장소 장애는 {@code CacheStore}와 달리 삼키지 않고 {@code DataAccessException}으로 올린다(인터페이스 설명 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RedisTokenVersionStore implements TokenVersionStore {

    /**
     * 키 접두사. 회원 모듈이 쓰고 모든 앱이 읽는 서비스 간 계약이라 버전을 붙인다.
     * 키나 값 형식을 바꾸면 {@code v2} 키를 새로 만들고, 옛 키를 읽는 앱이 남지 않을 때까지 두 키를 함께 쓴다.
     */
    static final String KEY_PREFIX = "member:token-version:v1:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public OptionalLong findMinVersion(long memberId) {
        String value = redisTemplate.opsForValue().get(key(memberId));
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            // 저장소 장애가 아니라 쓰는 쪽의 버그라, 장애처럼 검사를 멈추지 않고 이 값만 없는 것으로 본다.
            // 회원 쪽의 다시 채우기가 올바른 값으로 덮어쓰므로 지우지 않는다
            log.warn("토큰 최소 버전을 숫자로 읽지 못해 기록이 없는 것으로 본다. memberId={}", memberId);
            return OptionalLong.empty();
        }
    }

    @Override
    public void saveMinVersion(long memberId, long version, Duration ttl) {
        if (ttl == null || ttl.toMillis() < 1) {
            throw new IllegalArgumentException("TTL은 1밀리초 이상이어야 합니다.");
        }
        redisTemplate.opsForValue().set(key(memberId), Long.toString(version), ttl);
    }

    private static String key(long memberId) {
        return KEY_PREFIX + memberId;
    }

}
