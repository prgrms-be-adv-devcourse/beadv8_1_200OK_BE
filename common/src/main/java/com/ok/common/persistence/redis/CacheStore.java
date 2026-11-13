package com.ok.common.persistence.redis;

import java.time.Duration;
import java.util.Optional;

/**
 * 값을 시간 제한을 두고 임시로 저장하는 캐시 저장소다. 지금은 Redis(Lettuce)가 구현하고, 서비스는 이 인터페이스에만 의존한다.
 * 클라이언트나 저장소가 바뀌어도 구현체만 바뀌고 호출하는 쪽은 그대로이도록, Redis 명령이 아니라 캐시 동작으로 정의했다.
 * <p>키는 {@code 모듈:도메인:식별자} 형식으로 호출하는 쪽이 만든다(예: {@code delivery:shipping-fee:63100}). 다른 모듈과 키가 겹치지 않게 한다.
 * <p>값은 JSON 문자열로 저장하고 타입 정보는 싣지 않는다. 읽는 쪽이 타입을 정한다(Kafka 메시지와 같은 방식).
 * <p>캐시는 없어도 본 흐름이 동작해야 하므로, Redis 장애는 읽기와 쓰기에서는 흡수하고 삭제에서만 호출하는 쪽에 알린다. 분산락처럼 실패를 반드시 알려야 하는 동작은
 * 이 인터페이스에 넣지 않는다(docs/decisions/common/538-redis-client-lettuce.md).
 */
public interface CacheStore {

    /**
     * 키의 값을 {@code type}으로 읽는다. 키가 없으면 비어 있다.
     * Redis에 접근하지 못해도(장애, 타임아웃) 예외 대신 비어 있는 값(캐시 미스)을 돌려주고 경고 로그만 남긴다. 호출하는 쪽은 원본(DB 등)에서 읽으면 된다.
     * 저장된 JSON을 {@code type}으로 읽지 못하면(배포로 클래스 모양이 바뀐 경우 등)도 캐시 미스로 보고 그 키를 지운다. 지우지 않으면 같은 키가 계속 실패한다.
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * 값을 저장한다. 이미 있으면 덮어쓰고 TTL도 새로 정한다.
     * TTL은 필수다. 운영 Redis는 메모리가 차도 키를 지우지 않고 쓰기를 거절(noeviction)하므로, 만료 없는 키가 쌓이면 모든 쓰기가 막힌다.
     * Redis에 접근하지 못해 저장하지 못해도 경고 로그만 남기고 정상으로 끝난다. 저장 실패는 다음 조회가 캐시 미스가 될 뿐이다.
     *
     * @param ttl 1밀리초 이상. Redis가 밀리초 단위로 받는다
     * @throws IllegalArgumentException 키가 비었거나, 값이 null이거나, TTL이 1밀리초 미만이면
     */
    void put(String key, Object value, Duration ttl);

    /**
     * 키를 지운다. 없는 키여도 오류가 아니다.
     * 다른 동작과 달리 Redis에 접근하지 못하면 예외({@code DataAccessException})를 그대로 올린다. 삼키면 낡은 값이 TTL이 끝날 때까지 남는 것을
     * 호출한 쪽이 알 수 없다. 어떻게 할지(트랜잭션 실패, 짧은 TTL에 맡김 등)는 호출한 쪽이 정한다.
     */
    void evict(String key);

}
