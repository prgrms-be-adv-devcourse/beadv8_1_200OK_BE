package com.ok.product.review.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// 리뷰가 표현한 키워드 하나. 판정을 통과하지 못한 키워드는 행이 없다.
// 문구·분석 항목은 저장하지 않는다. 화면 문구는 현재 키워드 목록이 정하므로, 저장하면 목록을 고쳐도 기존 리뷰에 반영되지 않는다
@Entity
@Table(name = "review_expressed_keyword", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewExpressedKeyword extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "review_keyword_id", nullable = false)
	private ReviewKeyword reviewKeyword;

	@Column(name = "keyword_id", nullable = false, length = 10)
	private String keywordId;

	// 표현한 키워드는 ReviewKeyword를 통해서만
	static ReviewExpressedKeyword create(ReviewKeyword reviewKeyword, String keywordId) {
		ReviewExpressedKeyword expressedKeyword = new ReviewExpressedKeyword();
		expressedKeyword.reviewKeyword = reviewKeyword;
		expressedKeyword.keywordId = keywordId;
		return expressedKeyword;
	}
}
