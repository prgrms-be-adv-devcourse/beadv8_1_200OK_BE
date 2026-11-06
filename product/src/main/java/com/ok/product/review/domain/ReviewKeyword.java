package com.ok.product.review.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리뷰의 최신 키워드 분석 결과다. 리뷰마다 한 행이고, 리뷰가 표현한 키워드만 {@link ReviewExpressedKeyword}로 둔다.
 * 키워드를 하나도 표현하지 않은 리뷰도 이 행은 있어야 분석 완료 리뷰 수에 든다.
 * 어느 리뷰 내용으로, 어느 키워드 목록과 모델로 낸 결과인지 함께 남긴다.
 */
@Entity
@Table(name = "review_keyword", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewKeyword extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "review_id", nullable = false, updatable = false)
	private Long reviewId;

	// 분석할 때 읽은 리뷰의 수정 시각. 리뷰가 그 뒤에 수정됐는지 판별하는 기준이다
	@Column(name = "review_updated_at", nullable = false)
	private LocalDateTime reviewUpdatedAt;

	@Column(name = "keyword_version", nullable = false, length = 20)
	private String keywordVersion;

	// 모델명은 50자까지만 저장된다. 더 긴 모델로 바꾸면 결과를 저장하지 못해 요청이 대기로 남고 5초마다 다시 분석된다
	@Column(nullable = false, length = 50)
	private String model;

	@Column(name = "analyzed_at", nullable = false)
	private LocalDateTime analyzedAt;

	@OneToMany(mappedBy = "reviewKeyword", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ReviewExpressedKeyword> expressedKeywords = new ArrayList<>();

	/**
	 * 분석 결과를 만든다.
	 *
	 * @param expressedKeywordIds 판정을 통과한 키워드 ID. 비어 있어도 된다
	 */
	public static ReviewKeyword create(
			Long reviewId, LocalDateTime reviewUpdatedAt, Set<String> expressedKeywordIds,
			String keywordVersion, String model, LocalDateTime analyzedAt
	) {
		ReviewKeyword reviewKeyword = new ReviewKeyword();
		reviewKeyword.reviewId = reviewId;
		reviewKeyword.replace(reviewUpdatedAt, expressedKeywordIds, keywordVersion, model, analyzedAt);
		return reviewKeyword;
	}

	/**
	 * 수정된 리뷰를 다시 분석한 결과로 바꾼다. 리뷰마다 결과는 한 행이라 새 행을 만들지 않고 이 행을 고친다.
	 *
	 * @param expressedKeywordIds 판정을 통과한 키워드 ID. 비어 있어도 된다
	 */
	public void replace(
			LocalDateTime reviewUpdatedAt, Set<String> expressedKeywordIds,
			String keywordVersion, String model, LocalDateTime analyzedAt
	) {
		this.reviewUpdatedAt = reviewUpdatedAt;
		this.keywordVersion = keywordVersion;
		this.model = model;
		this.analyzedAt = analyzedAt;

		// 남는 키워드의 행은 그대로 둔다. 지우고 새로 넣으면 Hibernate가 INSERT를 DELETE보다 먼저 내보내 (결과, 키워드) UNIQUE에 걸린다
		Set<String> added = new HashSet<>(expressedKeywordIds);
		this.expressedKeywords.removeIf(row -> !expressedKeywordIds.contains(row.getKeywordId()));
		this.expressedKeywords.forEach(row -> added.remove(row.getKeywordId()));
		added.stream().sorted()
			.forEach(keywordId -> this.expressedKeywords.add(ReviewExpressedKeyword.create(this, keywordId)));
	}

	public List<ReviewExpressedKeyword> getExpressedKeywords() {
		return Collections.unmodifiableList(expressedKeywords);
	}
}
