package com.ok.product.product.domain;

import java.time.LocalDateTime;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구매자의 키워드 검색 한 번을 기록한다. 0건 검색어 조회와 검색 품질 분석의 입력이다.
 * 회원을 알 수 있는 값은 남기지 않는다. 쌓기만 하고 고치지 않으며, 보존 기간이 지난 행은 지운다.
 */
@Entity
@Table(name = "product_search_log", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSearchLog extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String keyword;

	@Column(name = "normalized_keyword", nullable = false, length = 50)
	private String normalizedKeyword;

	@Column(name = "result_count", nullable = false)
	private long resultCount;

	@Column(name = "latency_millis", nullable = false)
	private long latencyMillis;

	@Column(nullable = false)
	private boolean suggested;

	@Column(name = "searched_at", nullable = false)
	private LocalDateTime searchedAt;

	/**
	 * 검색 한 번의 기록을 만든다.
	 *
	 * @param keyword       구매자가 입력한 원문
	 * @param searchKeyword 원문을 정규화한 검색어. 공백을 없앤 형태로 남겨 띄어쓰기만 다른 검색어를 한 항목으로 묶는다
	 * @param resultCount   페이지 크기와 관계없는 전체 결과 수
	 */
	public static ProductSearchLog of(String keyword, ProductSearchKeyword searchKeyword, long resultCount,
		long latencyMillis, boolean suggested, LocalDateTime searchedAt) {
		ProductSearchLog log = new ProductSearchLog();
		log.keyword = keyword;
		log.normalizedKeyword = searchKeyword.compact();
		log.resultCount = resultCount;
		log.latencyMillis = latencyMillis;
		log.suggested = suggested;
		log.searchedAt = searchedAt;
		return log;
	}
}
