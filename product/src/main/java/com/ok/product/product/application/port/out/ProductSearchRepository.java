package com.ok.product.product.application.port.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.ok.product.product.domain.ProductSearch;

/**
 * 상품 검색 행 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ProductSearchRepository {

	Optional<ProductSearch> findById(Long productId);

	List<ProductSearch> findAllById(Iterable<Long> productIds);

	<S extends ProductSearch> S save(S productSearch);

	/**
	 * 검색 행이 없거나 정규화 버전이 {@code currentVersion}보다 낮은 미삭제 상품의 ID를 {@code afterId} 다음부터 오름차순으로 읽는다.
	 */
	List<Long> findBackfillTargetIds(long afterId, int currentVersion, int limit);

	/**
	 * 상품의 검색 키워드(태그와 동의어)를 바꾼다. 엔티티를 읽어 고치지 않고 이 칼럼만 한 문장으로 바꿔, 상품 수정이 계산한 다른 칼럼을 덮지 않는다.
	 *
	 * @return 바꾼 행 수. 검색 행이 아직 없으면 0이다
	 */
	int updateKeywords(Long productId, String keywords, LocalDateTime now);

}
