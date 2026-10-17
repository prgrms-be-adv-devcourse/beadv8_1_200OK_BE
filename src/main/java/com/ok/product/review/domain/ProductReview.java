package com.ok.product.review.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.review.exception.ProductReviewErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "product_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReview extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(name = "product_option_id", nullable = false)
	private Long productOptionId;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "order_item_id", nullable = false)
	private Long orderItemId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(nullable = false, columnDefinition = "TINYINT")
	private int rating;

	@Column(nullable = false, length = 2000)
	private String content;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	// 첨부는 컬렉션에서 빠지면 물리 삭제
	@OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("sortOrder ASC")
	private List<ProductReviewMedia> media = new ArrayList<>();

	public static ProductReview create(
			Long productId, Long productOptionId, Long orderId, Long orderItemId, Long memberId,
			int rating, String content, List<ProductReviewMediaSpec> mediaSpecs
	) {
		ProductReview review = new ProductReview();
		review.productId = productId;
		review.productOptionId = productOptionId;
		review.orderId = orderId;
		review.orderItemId = orderItemId;
		review.memberId = memberId;
		review.update(rating, content, mediaSpecs);
		return review;
	}

	public void update(int rating, String content, List<ProductReviewMediaSpec> mediaSpecs) {
		validateMediaSpecs(mediaSpecs);
		this.rating = rating;
		this.content = content;
		replaceMedia(mediaSpecs);
	}

	public void delete(LocalDateTime now) {
		if (deletedAt != null) {
			return;
		}
		deletedAt = now;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public List<ProductReviewMedia> getMedia() {
		return Collections.unmodifiableList(media);
	}

	// 개수가 수정 시 전체교체
	// 총 개수(6개)는 요청 DTO에서 막고, 타입별 제한은 생성·수정 모두 지켜야 하는 규칙이라 엔티티에서 검증한다
	private void validateMediaSpecs(List<ProductReviewMediaSpec> mediaSpecs) {
		long videoCount = mediaSpecs.stream()
				.filter(spec -> spec.type() == ProductReviewMediaType.VIDEO)
				.count();
		if (videoCount > 1) {
			throw new RestApiException(ProductReviewErrorCode.REVIEW_MEDIA_LIMIT_EXCEEDED);
		}
	}

	private void replaceMedia(List<ProductReviewMediaSpec> mediaSpecs) {
		media.clear();
		for (int i = 0; i < mediaSpecs.size(); i++) {
			media.add(ProductReviewMedia.create(this, mediaSpecs.get(i), i));
		}
	}
}
