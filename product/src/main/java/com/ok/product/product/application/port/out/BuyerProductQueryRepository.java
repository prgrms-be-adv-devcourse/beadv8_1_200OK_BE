package com.ok.product.product.application.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSort;

/**
 * 구매자 상품 목록의 상품 ID 한 페이지를 찾는 출력 포트. 구현은 {@code adapter.out.persistence}의 JdbcClient 구현이다.
 * 검색어 유무와 관계없이 구매자 목록은 모두 여기로 온다. 품절을 뒤로 보내는 규칙을 한 곳에만 두기 위해서다.
 * 점수를 계산하는 SQL이라 엔티티 대신 상품 ID만 돌려준다.
 */
public interface BuyerProductQueryRepository {

	/**
	 * 구매자에게 보이는 상품의 ID 한 페이지를 정렬 순서대로 읽는다. 어떤 정렬이든 품절 상품이 뒤에 온다.
	 * 필터와 검색어는 null이면 거르지 않는다. 검색어가 있으면 점수가 하한 이상인 상품만 결과와 전체 개수에 든다.
	 * 검색어가 한 글자({@link ProductSearchKeyword#isSingleCharacter()})면 점수 대신 한 글자 품목({@code search_item})이 그 글자와 같은 상품만 찾는다.
	 *
	 * @param keyword  정규화한 검색어. 없으면 null
	 * @param sort     {@link ProductSort#RELEVANCE}는 검색어가 있을 때만 쓴다. 한 글자 검색어면 보정 평점, 그다음 id 순이다
	 * @param minScore 두 글자 이상 검색어일 때 이보다 점수가 낮은 상품은 결과와 개수에서 뺀다
	 * @throws IllegalArgumentException 검색어 없이 {@link ProductSort#RELEVANCE}를 넘기면
	 * @throws org.springframework.dao.QueryTimeoutException SQL이 시간 제한을 넘으면
	 */
	Page<Long> findVisibleProductIds(ProductSearchKeyword keyword, ProductCategoryGroup categoryGroup,
		ProductCategory category, ProductRegion region, ProductSort sort, double minScore, Pageable pageable);

}
