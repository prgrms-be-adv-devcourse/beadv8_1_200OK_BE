package com.ok.product.review.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 언급된 분석 항목 하나의 감성. 언급 없음은 행이 없다
@Entity
@Table(name = "review_aspect_sentiment", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewAspectSentiment extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "review_sentiment_id", nullable = false)
	private ReviewSentiment reviewSentiment;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReviewAspect aspect;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Sentiment sentiment;

	// 항목 감성은 ReviewSentiment를 통해서만
	static ReviewAspectSentiment create(ReviewSentiment reviewSentiment, ReviewAspect aspect, Sentiment sentiment) {
		ReviewAspectSentiment aspectSentiment = new ReviewAspectSentiment();
		aspectSentiment.reviewSentiment = reviewSentiment;
		aspectSentiment.aspect = aspect;
		aspectSentiment.sentiment = sentiment;
		return aspectSentiment;
	}

	void changeSentiment(Sentiment sentiment) {
		this.sentiment = sentiment;
	}
}
