package com.ok.common.persistence.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CacheStore}를 Redis로 구현한다. 앱에서 Redis 클라이언트(Lettuce)와 Spring Data Redis를 쓰는 곳은 이 패키지뿐이다.
 * 키와 값을 모두 문자열로 다루고, 값은 앱의 {@link JsonMapper}로 JSON 변환한다. JDK 직렬화나 타입 정보 헤더를 쓰지 않아 클래스가 바뀌어도 저장된 값을 읽을 수 있다.
 * 호출하는 쪽에 공개하지 않으려고 패키지 비공개로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RedisCacheStore implements CacheStore {

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        requireKey(key);
        if (type == null) {
            throw new IllegalArgumentException("읽을 타입은 필수입니다.");
        }
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Redis에 접근하지 못해 캐시 미스로 처리한다. key={}", key, e);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jsonMapper.readValue(json, type));
        } catch (JacksonException e) {
            log.warn("캐시 값을 읽지 못해 지운다. key={}, type={}", key, type.getSimpleName(), e);
            deleteQuietly(key);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        requireKey(key);
        if (value == null) {
            throw new IllegalArgumentException("저장할 값은 필수입니다.");
        }
        if (ttl == null || ttl.toMillis() < 1) {
            throw new IllegalArgumentException("TTL은 1밀리초 이상이어야 합니다.");
        }
        try {
            redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(value), ttl);
        } catch (DataAccessException e) {
            log.warn("Redis에 접근하지 못해 캐시에 저장하지 못했다. 다음 조회는 캐시 미스가 된다. key={}", key, e);
        }
    }

    @Override
    public void evict(String key) {
        requireKey(key);
        redisTemplate.delete(key);
    }

    // 읽지 못한 값을 지우는 것은 캐시 미스로 돌려주기 위한 정리일 뿐이라, 지우지 못해도 미스로 돌려준다. 남은 값은 TTL이 끝나면 사라진다
    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.warn("읽지 못한 캐시 값을 지우지 못했다. TTL이 끝나면 사라진다. key={}", key, e);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("키는 필수입니다.");
        }
    }

}
