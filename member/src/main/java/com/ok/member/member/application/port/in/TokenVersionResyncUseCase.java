package com.ok.member.member.application.port.in;

/**
 * 토큰 검증기가 읽는 저장소의 토큰 최소 버전을 회원 DB 값으로 다시 채우는 유스케이스.
 * 저장소 장애 중 비밀번호를 바꿨거나, 저장소가 재시작·장애 조치로 기록을 잃었을 때 빠진 기록을 메운다.
 */
public interface TokenVersionResyncUseCase {

    /**
     * 최근 Access Token 수명 안에 토큰 버전을 올린 회원의 지금 버전을 저장소에 다시 남긴다.
     * 저장소에 접근하지 못하면 그 실행은 멈추고 다음 실행에 맡긴다.
     *
     * @return 남긴 회원 수
     */
    int resync();

}
