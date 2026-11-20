package com.ok.common.persistence.redis;

import com.ok.common.security.TokenVersionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

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

    /**
     * 지금 값보다 높을 때만 쓰는 스크립트. 규칙과 인자는 스크립트 파일의 주석에 있다.
     * IDE 지원을 받고 redis-cli로 따로 돌려 볼 수 있도록 코드 밖의 파일로 둔다.
     */
    private static final RedisScript<Long> SAVE_IF_HIGHER =
            RedisScript.of(new ClassPathResource("redis/token-version-save-if-higher.lua"), Long.class);

    static {
        // 파일은 처음 실행할 때 읽힌다. 경로가 틀리면 첫 비밀번호 변경에서야 드러나므로, 클래스를 올릴 때 미리 읽어 앱 시작에서 실패하게 한다
        SAVE_IF_HIGHER.getSha1();
    }

    private final StringRedisTemplate redisTemplate;

    @Override
    public OptionalLong findMinVersion(long memberId) {
        String value = translateStopped(() -> redisTemplate.opsForValue().get(key(memberId)));
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
        // 읽고 비교하고 쓰는 일을 스크립트 하나로 해 다른 쓰기가 끼어들지 못하게 한다. 키가 하나라 클러스터에서도 한 노드에서 돈다
        translateStopped(() -> redisTemplate.execute(SAVE_IF_HIGHER, List.of(key(memberId)),
                Long.toString(version), Long.toString(ttl.toMillis())));
    }

    // 앱 종료 중 연결 팩토리가 먼저 멈추면 Spring Data Redis가 변환하지 않은 IllegalStateException이 나온다.
    // 이것도 저장소에 닿지 못한 것이라 계약대로 DataAccessException으로 올린다. 그대로 두면 검증기의 장애 시 통과가 적용되지 않아
    // 종료 직전 남은 인증 요청이 500이 된다. 그 밖의 예외는 버그라 감싸지 않고 그대로 드러낸다
    private static <T> T translateStopped(Supplier<T> redisCall) {
        try {
            return redisCall.get();
        } catch (IllegalStateException e) {
            throw new RedisConnectionFailureException("토큰 버전 저장소의 Redis 연결을 쓸 수 없습니다.", e);
        }
    }

    private static String key(long memberId) {
        return KEY_PREFIX + memberId;
    }

}
