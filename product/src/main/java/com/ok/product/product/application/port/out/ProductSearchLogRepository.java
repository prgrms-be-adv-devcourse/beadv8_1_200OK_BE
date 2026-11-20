package com.ok.product.product.application.port.out;

import java.time.LocalDateTime;
import java.util.List;

import com.ok.product.product.application.dto.ZeroResultKeywordResult;

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

	/**
	 * 검색 시각이 {@code from} 이상 {@code to} 미만인 0건 검색을 정규화 검색어로 묶어 횟수가 많은 순으로 {@code limit}개 돌려준다.
	 * 횟수가 같으면 마지막 검색 시각이 늦은 순, 그다음 정규화 검색어 순이다.
	 */
	List<ZeroResultKeywordResult> findZeroResultKeywords(LocalDateTime from, LocalDateTime to, int limit);

}
