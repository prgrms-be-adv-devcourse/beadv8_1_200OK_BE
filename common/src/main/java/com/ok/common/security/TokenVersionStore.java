package com.ok.common.security;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * 회원별 토큰 최소 버전을 저장하고 읽는다. 이 값보다 낮은 버전({@link TokenClaims#TOKEN_VERSION})으로 발급된 Access Token은 거부된다(#561).
 * 버전을 올리는 회원 쪽과 모든 앱의 토큰 검증기가 같은 저장 위치·키·값 형식을 쓰도록 common에 둔다. 서비스가 나뉘면 이 저장 형식이 서비스 간 계약이 된다.
 * {@code CacheStore}와 달리 저장소에 접근하지 못하면 예외를 그대로 올린다. 검증기가 "기록 없음"과 "저장소 장애"를 구분해
 * 장애 때만 조회를 잠시 멈추고, 회원 쪽은 저장 실패를 알고 다시 채울 수 있어야 하기 때문이다.
 * 원본은 회원 DB의 토큰 버전이고, 이 저장소는 최근에 버전을 올린 회원만 담은 사본이다.
 */
public interface TokenVersionStore {

    /**
     * 회원의 토큰 최소 버전을 읽는다.
     *
     * @return 최소 버전. 기록이 없으면(최근에 버전을 올린 적이 없거나 기록 시간이 지남) 비어 있다
     * @throws org.springframework.dao.DataAccessException 저장소에 접근하지 못하면(장애, 타임아웃)
     */
    OptionalLong findMinVersion(long memberId);

    /**
     * 회원의 토큰 최소 버전을 저장한다. 지금 기록보다 높을 때만 바꾸고, 같거나 낮으면 무시한다.
     * 최소 버전은 내려가면 안 된다. 쓰기가 늦게 도착하는 순서(연속 비밀번호 변경, 그 사이에 DB를 읽은 다시 채우기)에 상관없이
     * 가장 높은 버전이 남아야 두 변경 사이에 발급된 토큰까지 거부된다.
     *
     * @param ttl 기록을 남길 시간. 버전을 올리기 전에 발급된 Access Token이 모두 만료될 때까지 남아야 한다. 1밀리초 이상
     * @throws org.springframework.dao.DataAccessException 저장소에 접근하지 못하면(장애, 타임아웃)
     * @throws IllegalArgumentException                    TTL이 1밀리초 미만이면
     */
    void saveMinVersion(long memberId, long version, Duration ttl);

}
