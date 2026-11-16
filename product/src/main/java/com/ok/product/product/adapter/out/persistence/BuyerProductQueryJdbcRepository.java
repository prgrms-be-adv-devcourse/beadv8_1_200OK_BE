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
import com.ok.product.product.application.port.out.BuyerProductQueryRepository;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSort;

import lombok.RequiredArgsConstructor;

/**
 * {@link BuyerProductQueryRepository}의 JdbcClient 구현.
 * Querydsl은 {@code MATCH ... AGAINST}를 쓰려면 Hibernate {@code FunctionContributor}로 함수를 등록하는 전역 설정이 더 필요하고,
 * {@code @Query} 네이티브 쿼리는 정렬을 바꾸려면 CASE를 겹겹이 써야 한다. 그래서 코드에 고정된 SQL 조각만 이어 붙이고 값은 모두 바인딩한다.
 */
@Repository
@RequiredArgsConstructor
public class BuyerProductQueryJdbcRepository implements BuyerProductQueryRepository {

	// 가중치(3, 1, 1, 0.5)는 E2 실험 값이다. 오타용 자모 일치는 다른 품목과도 잘 겹쳐 가장 작게 둔다
	private static final String SCORE = """
		MATCH(s.name_compact) AGAINST(:compact) * 3
		     + MATCH(s.search_body) AGAINST(:text)
		     + MATCH(s.keywords) AGAINST(:text)
		     + MATCH(s.name_jamo) AGAINST(:jamo) * 0.5""";

	// 품절 여부를 상품에 저장하지 않고 Product.isSoldOut()과 같은 기준(삭제되지 않은 옵션의 가용 재고 합 <= 0)으로 매번 계산한다.
	// 저장하면 재고 배정·해제·일부 해제·재고 수정 경로가 모두 그 값을 함께 고쳐야 하고, 하나라도 빠지면 목록 순서만 조용히 틀어진다.
	// 동점 정렬은 리뷰 5개 분량의 별 4.0을 미리 더한 베이지안 평균이다. rating_sum은 10점 척도(별 0.5 단위)라 4.0 × 2 × 5 = 40.0을 더한다
	private static final String SOLD_OUT_AND_RATING = """
		(SELECT COALESCE(SUM(o.total_stock - o.allocated_quantity), 0)
		          FROM %1$s.product_option o
		         WHERE o.product_id = p.id AND o.deleted_at IS NULL) <= 0 AS sold_out,
		       (p.rating_sum + 40.0) / (p.review_count + 5) AS rating_score""".formatted(ProductSchema.NAME);

	// 노출 조건은 VISIBLE과 미삭제다. 판매 상태는 거르지 않고 화면이 status로 표시한다.
	// 네이티브 SQL은 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
	private static final String PRODUCTS = " FROM %1$s.product p".formatted(ProductSchema.NAME);
	private static final String SEARCH_ROWS = " JOIN %1$s.product_search s ON s.product_id = p.id"
		.formatted(ProductSchema.NAME);
	private static final String VISIBLE = " WHERE p.visibility = 'VISIBLE' AND p.deleted_at IS NULL";

	// MATCH를 WHERE에 두지 않아 겹치는 글자가 없는 상품도 점수 0으로 남는다. 하한이 이런 상품까지 함께 뺀다
	private static final String SCORE_FLOOR = " HAVING score >= :minScore";

	private final JdbcClient jdbcClient;

	@Override
	public Page<Long> findVisibleProductIds(ProductSearchKeyword keyword, ProductCategoryGroup categoryGroup,
		ProductCategory category, ProductRegion region, ProductSort sort, double minScore, Pageable pageable) {
		if (keyword == null && sort == ProductSort.RELEVANCE) {
			throw new IllegalArgumentException("검색어 없이 정확도순으로 정렬할 수 없습니다.");
		}
		Map<String, Object> params = new HashMap<>();
		// 검색어가 없으면 product_search를 JOIN하지 않는다. JOIN하면 검색 행이 아직 없는 상품(백필 전, 백필 실패)이 목록에서 사라진다
		StringBuilder from = new StringBuilder(PRODUCTS);
		if (keyword != null) {
			from.append(SEARCH_ROWS);
			params.put("compact", keyword.compact());
			params.put("text", keyword.text());
			params.put("jamo", keyword.jamo());
			params.put("minScore", minScore);
		}
		from.append(VISIBLE);
		if (categoryGroup != null) {
			from.append(" AND p.category IN (:categories)");
			params.put("categories", ProductCategory.findAllByGroup(categoryGroup).stream().map(Enum::name).toList());
		}
		if (category != null) {
			from.append(" AND p.category = :category");
			params.put("category", category.name());
		}
		if (region != null) {
			from.append(" AND p.region = :region");
			params.put("region", region.name());
		}

		String content = keyword == null
			? "SELECT p.id, " + SOLD_OUT_AND_RATING + from
			: "SELECT p.id, " + SCORE + " AS score, " + SOLD_OUT_AND_RATING + from + SCORE_FLOOR;
		List<Long> productIds = jdbcClient.sql(content + " ORDER BY " + orderBy(sort) + " LIMIT :size OFFSET :offset")
			.params(params)
			.param("size", pageable.getPageSize())
			.param("offset", pageable.getOffset())
			.query((rs, rowNum) -> rs.getLong("id"))
			.list();

		// 하한 미만 상품이 개수에 들지 않도록 검색어가 있으면 같은 조건을 감싸서 센다
		String count = keyword == null
			? "SELECT COUNT(*)" + from
			: "SELECT COUNT(*) FROM (SELECT " + SCORE + " AS score" + from + SCORE_FLOOR + ") t";
		return PageableExecutionUtils.getPage(productIds, pageable, () -> jdbcClient.sql(count)
			.params(params)
			.query(Long.class)
			.single());
	}

	// 마지막 키를 고유한 p.id로 두어 순서를 하나로 정한다. 앞 키가 같은 상품끼리 순서가 매번 달라지면 페이지를 넘길 때 중복·누락이 생긴다
	private static String orderBy(ProductSort sort) {
		return switch (sort) {
			case RELEVANCE -> "sold_out, score DESC, rating_score DESC, p.id DESC";
			case PRICE_ASC -> "sold_out, p.min_sale_price, rating_score DESC, p.id DESC";
			case PRICE_DESC -> "sold_out, p.min_sale_price DESC, rating_score DESC, p.id DESC";
			case LATEST -> "sold_out, p.id DESC";
		};
	}
}
