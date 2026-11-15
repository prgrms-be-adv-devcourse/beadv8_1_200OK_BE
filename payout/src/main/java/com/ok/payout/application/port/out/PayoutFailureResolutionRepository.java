package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutFailureResolution;

import java.util.List;

/**
 * 정산 실패 해결 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutFailureResolutionRepository {

    <S extends PayoutFailureResolution> List<S> saveAll(Iterable<S> resolutions);
}
