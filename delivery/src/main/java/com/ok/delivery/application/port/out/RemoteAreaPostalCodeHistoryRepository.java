package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.RemoteAreaPostalCodeHistory;

/**
 * 도서산간 우편번호 변경 이력 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface RemoteAreaPostalCodeHistoryRepository {

    <S extends RemoteAreaPostalCodeHistory> S save(S entity);
}
