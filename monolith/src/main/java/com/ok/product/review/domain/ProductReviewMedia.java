package com.ok.product.review.domain;

import com.ok.common.jpa.entity.BaseTime;
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

// 소프트 삭제 없음. ProductReview의 media 컬렉션에서 빠지면 orphanRemoval로 물리 삭제
@Entity
@Table(name = "product_review_media")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReviewMedia extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_review_id", nullable = false)
	private ProductReview review;

	// media 모듈의 objectKey. URL은 조회할 때 media 모듈에서 만든다
	@Column(name = "object_key", nullable = false)
	private String objectKey;

	// 목록에서 이미지와 영상을 다르게 보여 주려고 저장한다
	@Enumerated(EnumType.STRING)
	@Column(name = "media_type", nullable = false, length = 10)
	private ProductReviewMediaType mediaType;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	// 첨부는 ProductReview를 통해서만
	static ProductReviewMedia create(ProductReview review, ProductReviewMediaSpec spec, int sortOrder) {
		ProductReviewMedia media = new ProductReviewMedia();
		media.review = review;
		media.objectKey = spec.objectKey();
		media.mediaType = spec.type();
		media.sortOrder = sortOrder;
		return media;
	}
}
