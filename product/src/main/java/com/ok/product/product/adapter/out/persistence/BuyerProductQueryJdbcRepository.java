package com.ok.product.product.adapter.out.persistence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
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

/**
 * {@link BuyerProductQueryRepository}의 JdbcClient 구현.
 * Querydsl은 {@code MATCH ... AGAINST}를 쓰려면 Hibernate {@code FunctionContributor}로 함수를 등록하는 전역 설정이 더 필요하고,
 * {@code @Query} 네이티브 쿼리는 정렬을 바꾸려면 CASE를 겹겹이 써야 한다. 그래서 코드에 고정된 SQL 조각만 이어 붙이고 값은 모두 바인딩한다.
 */
@Repository
public class BuyerProductQueryJdbcRepository implements BuyerProductQueryRepository {

	// 가중치(3, 1, 1, 0.5)는 E2 실험 값이다. 오타용 자모 일치는 다른 품목과도 잘 겹쳐 가장 작게 둔다
	private static final String TEXT_SCORE = """
		MATCH(s.name_compact) AGAINST(:compact) * 3
		     + MATCH(s.search_body) AGAINST(:text)
		     + MATCH(s.keywords) AGAINST(:text)""";
	private static final String SCORE = TEXT_SCORE + " + MATCH(s.name_jamo) AGAINST(:jamo) * 0.5";

	// 자모 조각은 다른 품목과도 잘 겹친다("청포도"와 "청도 복숭아"). 그래서 글자가 그대로 겹친 상품이 하나라도 있으면
	// 자모로만 걸린 상품을 빼고, 하나도 없을 때(음절이 겹치지 않는 오타)만 자모 결과를 쓴다.
	// 윈도 함수는 HAVING 뒤에 계산되므로 하한을 넘은 상품 중에서 글자 일치를 찾는다
	private static final String SCORE_COLUMNS = SCORE + " AS score, " + TEXT_SCORE + " AS text_score, MAX("
		+ TEXT_SCORE + ") OVER () AS best_text_score";
	private static final String JAMO_FALLBACK = " WHERE (p.text_score > 0 OR p.best_text_score = 0)";

	// 오타 교정 제안 품목이 상품명에 든 상품을 정확도순 맨 앞에 둔다. 자모 점수로는 "시레기"의 시래기 상품이 자모만 겹친
	// 다른 품목 아래에 오고, 글자 일치로는 "고추가루"의 고춧가루 상품이 "고추"가 겹친 상품 아래에 온다(#670).
	// 결과와 전체 개수는 그대로 두고 순서만 바꾼다. 제안을 결과에 더하면 F7이 하지 않기로 한 자동 치환이 된다
	private static final String SUGGESTED_COLUMN = ", s.name_compact LIKE :suggestedItem AS suggested";

	// 검색어에 가공 표현이 없으면 농산 가공품 점수에 곱한다. E0 평가셋에서 0.3~0.7 중 clean·spacing MRR이 가장 높았다.
	// 하한과 자모 대체는 곱하기 전 점수로 판정해 결과와 전체 개수는 그대로 두고 순서만 바꾼다
	private static final double PROCESSED_DEMOTION = 0.5;
	private static final String RANK_SCORE =
		"p.score * CASE WHEN p.category = '%s' AND NOT :processedIntent THEN %s ELSE 1 END AS score".formatted(
			ProductCategory.PROCESSED_AGRI.name(), PROCESSED_DEMOTION);

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

	// 시간 제한이 걸린 전용 JdbcClient만 쓴다(BuyerProductQueryJdbcConfig). 기본 빈은 다른 모듈도 쓰므로 바꾸지 않는다
	public BuyerProductQueryJdbcRepository(
		@Qualifier(BuyerProductQueryJdbcConfig.JDBC_CLIENT) JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public Page<Long> findVisibleProductIds(ProductSearchKeyword keyword, ProductCategoryGroup categoryGroup,
		ProductCategory category, ProductRegion region, ProductSort sort, double minScore, String suggestedItem,
		Pageable pageable) {
		if (keyword == null && sort == ProductSort.RELEVANCE) {
			throw new IllegalArgumentException("검색어 없이 정확도순으로 정렬할 수 없습니다.");
		}
		Match match = Match.of(keyword);
		boolean suggested = match == Match.FULLTEXT && sort == ProductSort.RELEVANCE && suggestedItem != null;
		Map<String, Object> params = new HashMap<>();
		StringBuilder from = new StringBuilder(PRODUCTS);
		switch (match) {
			// 검색어가 없으면 product_search를 JOIN하지 않는다. JOIN하면 검색 행이 아직 없는 상품(백필 전, 백필 실패)이 목록에서 사라진다
			case NONE -> from.append(VISIBLE);
			case ITEM -> {
				from.append(SEARCH_ROWS).append(VISIBLE).append(" AND s.search_item = :item");
				params.put("item", keyword.compact());
			}
			// 점수와 "글자 일치가 있는가"는 노출 상품 전체로 구한 뒤에 지역·분류 조건을 건다. 조건을 안쪽에 두면 조건에 맞는
			// 상품 중에 글자 일치가 없을 때 자모 결과로 되돌아가, 조건을 걸지 않았을 때 없던 상품이 나온다
			case FULLTEXT -> {
				from.setLength(0);
				from.append(" FROM (SELECT p.id, p.category, p.region, p.min_sale_price, p.rating_sum, p.review_count, ")
					.append(SCORE_COLUMNS).append(suggested ? SUGGESTED_COLUMN : "")
					.append(PRODUCTS).append(SEARCH_ROWS).append(VISIBLE).append(SCORE_FLOOR)
					.append(") p").append(JAMO_FALLBACK);
				params.put("compact", keyword.compact());
				params.put("text", keyword.text());
				params.put("jamo", keyword.jamo());
				params.put("minScore", minScore);
				params.put("processedIntent", keyword.processedIntent());
				if (suggested) {
					// 제안 품목은 품목 사전의 compact 값이라 LIKE 특수 문자(%, _)가 없다
					params.put("suggestedItem", "%" + suggestedItem + "%");
				}
			}
		}
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

		// 품목 모드는 점수를 계산하지 않고 모두 같은 점수(0)로 둔다. 정확도순은 보정 평점, 그다음 id 순이 된다.
		// 하한도 붙이지 않는다. 붙이면 점수 0인 상품이 모두 빠진다
		String content = switch (match) {
			case NONE -> "SELECT p.id, " + SOLD_OUT_AND_RATING + from;
			case ITEM -> "SELECT p.id, 0 AS score, " + SOLD_OUT_AND_RATING + from;
			case FULLTEXT -> "SELECT p.id, " + RANK_SCORE + (suggested ? ", p.suggested" : "") + ", "
				+ SOLD_OUT_AND_RATING + from;
		};
		String orderBy = " ORDER BY " + orderBy(sort, suggested);
		List<Long> productIds = jdbcClient.sql(content + orderBy + " LIMIT :size OFFSET :offset")
			.params(params)
			.param("size", pageable.getPageSize())
			.param("offset", pageable.getOffset())
			.query((rs, rowNum) -> rs.getLong("id"))
			.list();

		// 목록과 같은 조건으로 세어, 결과에서 뺀 상품이 개수에 들지 않게 한다
		return PageableExecutionUtils.getPage(productIds, pageable, () -> jdbcClient.sql("SELECT COUNT(*)" + from)
			.params(params)
			.query(Long.class)
			.single());
	}

	// 마지막 키를 고유한 p.id로 두어 순서를 하나로 정한다. 앞 키가 같은 상품끼리 순서가 매번 달라지면 페이지를 넘길 때 중복·누락이 생긴다
	private static String orderBy(ProductSort sort, boolean suggested) {
		return switch (sort) {
			case RELEVANCE -> suggested
				? "sold_out, suggested DESC, score DESC, rating_score DESC, p.id DESC"
				: "sold_out, score DESC, rating_score DESC, p.id DESC";
			case PRICE_ASC -> "sold_out, p.min_sale_price, rating_score DESC, p.id DESC";
			case PRICE_DESC -> "sold_out, p.min_sale_price DESC, rating_score DESC, p.id DESC";
			case LATEST -> "sold_out, p.id DESC";
		};
	}

	private enum Match {
		NONE,
		// ngram FULLTEXT는 두 글자 조각만 인덱싱해 한 글자 검색어("배")는 어떤 상품과도 겹치지 않는다.
		// 그래서 등록할 때 정해 둔 한 글자 품목(search_item)과 정확히 같은 상품을 찾는다. LIKE는 상품명이 산지로 시작해 맞지 않는다
		ITEM,
		FULLTEXT;

		static Match of(ProductSearchKeyword keyword) {
			if (keyword == null) {
				return NONE;
			}
			return keyword.isSingleCharacter() ? ITEM : FULLTEXT;
		}
	}
}
