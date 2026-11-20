package com.ok.member.auth.application.port.in;

/**
 * 만료된 Refresh Token 기록을 정리하는 유스케이스.
 */
public interface RefreshTokenCleanupUseCase {

    /**
     * 지금 시각에 이미 만료된 Refresh Token 기록을 모두 지운다.
     *
     * @param batchSize 트랜잭션 하나에서 지울 최대 행 수. 1 이상이어야 한다
     * @return 지운 행 수
     */
    int deleteExpired(int batchSize);

}
