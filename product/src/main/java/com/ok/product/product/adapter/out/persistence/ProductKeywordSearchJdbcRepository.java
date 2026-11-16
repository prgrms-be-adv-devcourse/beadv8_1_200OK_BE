package com.ok.product.product.adapter.out.persistence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ok.product.ProductSchema;
import com.ok.product.product.application.port.out.ProductKeywordSearchRepository;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearchKeyword;

import lombok.RequiredArgsConstructor;

/**
 * {@link ProductKeywordSearchRepository}의 JdbcClient 구현.
 * Querydsl은 {@code MATCH ... AGAINST}를 쓰려면 Hibernate {@code FunctionContributor}로 함수를 등록하는 전역 설정이 더 필요하고,
 * {@code @Query} 네이티브 쿼리는 정렬을 바꾸려면 CASE를 겹겹이 써야 한다. 그래서 코드에 고정된 SQL 조각만 이어 붙이고 값은 모두 바인딩한다.
 */
@Repository
@RequiredArgsConstructor
public class ProductKeywordSearchJdbcRepository implements ProductKeywordSearchRepository {

	// 가중치(3, 1, 1, 0.5)는 E2 실험 값이다. 오타용 자모 일치는 다른 품목과도 잘 겹쳐 가장 작게 둔다.
	// 노출 조건은 ProductQuerydslRepository의 구매자 목록과 같다. 판매 상태는 거르지 않고 화면이 status로 표시한다.
	// 동점 정렬은 리뷰 5개 분량의 별 4.0을 미리 더한 베이지안 평균이다. rating_sum은 10점 척도(별 0.5 단위)라 4.0 × 2 × 5 = 40.0을 더한다.
	// 네이티브 SQL은 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
	private static final String MATCHED_PRODUCTS = """
		SELECT p.id,
		       MATCH(s.name_compact) AGAINST(:compact) * 3
		     + MATCH(s.search_body) AGAINST(:text)
		     + MATCH(s.keywords) AGAINST(:text)
		     + MATCH(s.name_jamo) AGAINST(:jamo) * 0.5 AS score,
		       (p.rating_sum + 40.0) / (p.review_count + 5) AS rating_score
		  FROM %1$s.product p
		  JOIN %1$s.product_search s ON s.product_id = p.id
		 WHERE p.visibility = 'VISIBLE' AND p.deleted_at IS NULL
		""".formatted(ProductSchema.NAME);

	private final JdbcClient jdbcClient;

	@Override
	public Page<Long> findVisibleProductIds(ProductSearchKeyword keyword, ProductCategoryGroup categoryGroup,
		ProductCategory category, ProductRegion region, double minScore, Pageable pageable) {
		StringBuilder sql = new StringBuilder(MATCHED_PRODUCTS);
		Map<String, Object> params = new HashMap<>();
		params.put("compact", keyword.compact());
		params.put("text", keyword.text());
		params.put("jamo", keyword.jamo());
		if (categoryGroup != null) {
			sql.append(" AND p.category IN (:categories)");
			params.put("categories", ProductCategory.findAllByGroup(categoryGroup).stream().map(Enum::name).toList());
		}
		if (category != null) {
			sql.append(" AND p.category = :category");
			params.put("category", category.name());
		}
		if (region != null) {
			sql.append(" AND p.region = :region");
			params.put("region", region.name());
		}
		// MATCH를 WHERE에 두지 않아 겹치는 글자가 없는 상품도 점수 0으로 남는다. 하한이 이런 상품까지 함께 뺀다
		sql.append(" HAVING score >= :minScore");
		params.put("minScore", minScore);
		String matched = sql.toString();

		List<Long> productIds = jdbcClient.sql(matched
				+ " ORDER BY score DESC, rating_score DESC, p.id DESC LIMIT :size OFFSET :offset")
			.params(params)
			.param("size", pageable.getPageSize())
			.param("offset", pageable.getOffset())
			.query((rs, rowNum) -> rs.getLong("id"))
			.list();

		// 하한 미만 상품이 개수에 들지 않도록 같은 조건을 감싸서 센다
		return PageableExecutionUtils.getPage(productIds, pageable, () -> jdbcClient.sql(
				"SELECT COUNT(*) FROM (" + matched + ") t")
			.params(params)
			.query(Long.class)
			.single());
	}
}
