package com.ok.common.persistence.redis;

import java.time.Duration;
import java.util.Optional;

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
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jsonMapper.readValue(json, type));
        } catch (JacksonException e) {
            log.warn("캐시 값을 읽지 못해 지운다. key={}, type={}", key, type.getSimpleName(), e);
            redisTemplate.delete(key);
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
        redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(value), ttl);
    }

    @Override
    public void evict(String key) {
        requireKey(key);
        redisTemplate.delete(key);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("키는 필수입니다.");
        }
    }

}
