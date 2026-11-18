package com.ok.member.member.application.port.out;

import java.time.Duration;

/**
 * 회원의 토큰 최소 버전을 모든 앱의 토큰 검증기가 읽는 저장소에 남기는 출력 포트. 구현은 {@code adapter.out}에 있다.
 * 원본은 회원 DB의 토큰 버전이고, 저장소에는 최근에 버전을 올린 회원만 Access Token 수명 동안 남긴다.
 */
public interface TokenVersionRecorder {

    /**
     * 토큰 최소 버전을 남긴다. 이미 있으면 덮어쓴다.
     *
     * @param ttl 기록을 남길 시간. 버전을 올리기 전에 발급된 Access Token이 모두 만료될 때까지다
     * @throws org.springframework.dao.DataAccessException 저장소에 접근하지 못하면
     */
    void record(Long memberId, long tokenVersion, Duration ttl);

}
