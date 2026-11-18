package com.ok.product.product.application.port.out;

import java.time.LocalDateTime;

/**
 * 검색어 로그 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ProductSearchLogRepository {

	/**
	 * 검색 시각이 기준보다 앞선 로그를 지운다.
	 *
	 * @return 지운 행 수
	 */
	int deleteBySearchedAtBefore(LocalDateTime cutoff);

}
