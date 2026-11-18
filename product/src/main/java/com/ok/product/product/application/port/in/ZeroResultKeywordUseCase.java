package com.ok.product.product.application.port.in;

import java.util.List;

import com.ok.product.product.application.dto.ZeroResultKeywordQuery;
import com.ok.product.product.application.dto.ZeroResultKeywordResult;

/**
 * 결과가 0건이었던 검색어를 보는 관리자 유스케이스.
 */
public interface ZeroResultKeywordUseCase {

	/**
	 * 기간 안의 0건 검색을 정규화 검색어로 묶어 횟수가 많은 순으로 돌려준다.
	 * 횟수가 같으면 마지막 검색 시각이 늦은 순, 그다음 정규화 검색어 순이다.
	 *
	 * @throws com.ok.common.exception.RestApiException 시작일이 종료일보다 늦으면 {@code INVALID_SEARCH_LOG_PERIOD},
	 *                                                  시작일이 보존 기간 밖이면 {@code SEARCH_LOG_PERIOD_BEYOND_RETENTION}
	 */
	List<ZeroResultKeywordResult> getZeroResultKeywords(ZeroResultKeywordQuery query);

}
