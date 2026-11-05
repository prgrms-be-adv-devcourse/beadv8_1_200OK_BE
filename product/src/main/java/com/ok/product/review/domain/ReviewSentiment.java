package com.ok.product.review.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리뷰의 최신 감성 분석 결과다. 리뷰마다 한 행이고, 언급된 분석 항목의 감성만 {@link ReviewAspectSentiment}로 둔다.
 * 어느 리뷰 내용으로, 어느 분류 기준과 모델로 낸 결과인지 함께 남긴다.
 */
@Entity
@Table(name = "review_sentiment", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewSentiment extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "review_id", nullable = false, updatable = false)
	private Long reviewId;

	// 분석할 때 읽은 리뷰의 수정 시각. 리뷰가 그 뒤에 수정됐는지 판별하는 기준이다
	@Column(name = "review_updated_at", nullable = false)
	private LocalDateTime reviewUpdatedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Sentiment overall;

	@Column(name = "criteria_version", nullable = false, length = 20)
	private String criteriaVersion;

	// 모델명은 50자까지만 저장된다. 더 긴 모델로 바꾸면 결과를 저장하지 못해 요청이 대기로 남고 5초마다 다시 분석된다
	@Column(nullable = false, length = 50)
	private String model;

	@Column(name = "analyzed_at", nullable = false)
	private LocalDateTime analyzedAt;

	@OneToMany(mappedBy = "reviewSentiment", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ReviewAspectSentiment> aspects = new ArrayList<>();

	/**
	 * 분석 결과를 만든다. 언급 없음인 항목은 행을 만들지 않는다.
	 *
	 * @param aspects 여섯 분석 항목 모두의 감성
	 */
	public static ReviewSentiment create(
			Long reviewId, LocalDateTime reviewUpdatedAt, Sentiment overall, Map<ReviewAspect, AspectSentiment> aspects,
			String criteriaVersion, String model, LocalDateTime analyzedAt
	) {
		ReviewSentiment sentiment = new ReviewSentiment();
		sentiment.reviewId = reviewId;
		sentiment.replace(reviewUpdatedAt, overall, aspects, criteriaVersion, model, analyzedAt);
		return sentiment;
	}

	/**
	 * 수정된 리뷰를 다시 분석한 결과로 바꾼다. 리뷰마다 결과는 한 행이라 새 행을 만들지 않고 이 행을 고친다.
	 *
	 * @param aspects 여섯 분석 항목 모두의 감성
	 */
	public void replace(
			LocalDateTime reviewUpdatedAt, Sentiment overall, Map<ReviewAspect, AspectSentiment> aspects,
			String criteriaVersion, String model, LocalDateTime analyzedAt
	) {
		this.reviewUpdatedAt = reviewUpdatedAt;
		this.overall = overall;
		this.criteriaVersion = criteriaVersion;
		this.model = model;
		this.analyzedAt = analyzedAt;

		Map<ReviewAspect, Sentiment> mentioned = new EnumMap<>(ReviewAspect.class);
		aspects.forEach((aspect, aspectSentiment) -> aspectSentiment.mentioned()
				.ifPresent(sentiment -> mentioned.put(aspect, sentiment)));
		// 이미 있는 항목은 행을 고쳐 쓴다. 지우고 새로 넣으면 Hibernate가 INSERT를 DELETE보다 먼저 내보내 (결과, 항목) UNIQUE에 걸린다
		this.aspects.removeIf(row -> !mentioned.containsKey(row.getAspect()));
		for (ReviewAspectSentiment row : this.aspects) {
			row.changeSentiment(mentioned.remove(row.getAspect()));
		}
		mentioned.forEach((aspect, sentiment) -> this.aspects.add(ReviewAspectSentiment.create(this, aspect, sentiment)));
	}

	public List<ReviewAspectSentiment> getAspects() {
		return Collections.unmodifiableList(aspects);
	}
}
