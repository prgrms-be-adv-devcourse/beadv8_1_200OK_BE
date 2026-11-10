package com.ok.product.review.domain;

import com.ok.product.ProductSchema;
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

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "product_review", catalog = ProductSchema.NAME)
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

	/**
	 * 감성 분석할 내용이 있는지 본다. 한글 자모가 아닌 글자가 하나라도 있어야 내용이 있다고 본다.
	 * 공백, 기호, 이모지, 숫자, "ㅋㅋㅋ"·"ㅠㅠ" 같은 자음·모음만 있는 본문은 분석기를 불러도 감성을 판단할 근거가 없다.
	 * 음절을 자모로 풀어 보내는 입력기(NFD)도 있어, 자모를 음절로 합친 뒤 본다.
	 */
	public boolean hasAnalyzableContent() {
		return isAnalyzableContent(content);
	}

	/**
	 * 리뷰가 아닌 본문(관리자 분석 테스트)에도 {@link #hasAnalyzableContent()}와 같은 규칙을 적용한다.
	 * 두 경로의 규칙이 어긋나면 테스트에서 분석한 본문을 운영 작업자는 내용 없는 리뷰로 건너뛴다.
	 */
	public static boolean isAnalyzableContent(String content) {
		return Normalizer.normalize(content, Normalizer.Form.NFC).codePoints()
			.anyMatch(codePoint -> Character.isLetter(codePoint) && !isHangulJamo(codePoint));
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

	// 호환용 한글 자모(ㄱ~ㆎ)와 한글 자모 블록
	private static boolean isHangulJamo(int codePoint) {
		return (codePoint >= 0x3131 && codePoint <= 0x318E) || (codePoint >= 0x1100 && codePoint <= 0x11FF);
	}

	private void replaceMedia(List<ProductReviewMediaSpec> mediaSpecs) {
		media.clear();
		for (int i = 0; i < mediaSpecs.size(); i++) {
			media.add(ProductReviewMedia.create(this, mediaSpecs.get(i), i));
		}
	}
}
