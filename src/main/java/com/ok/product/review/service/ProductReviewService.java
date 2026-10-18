package com.ok.product.review.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ProductReviewMedia;
import com.ok.product.review.domain.ProductReviewMediaSpec;
import com.ok.product.review.exception.ProductReviewErrorCode;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.service.dto.ProductReviewCreateCommand;
import com.ok.product.review.service.dto.ProductReviewMediaResult;
import com.ok.product.review.service.dto.ProductReviewResult;
import com.ok.product.review.service.dto.ProductReviewUpdateCommand;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductReviewService {

	private final ProductReviewRepository productReviewRepository;
	private final ProductRepository productRepository;
	private final ProductOptionRepository productOptionRepository;
	private final ProductReviewMediaValidator mediaValidator;
	private final MediaUrlResolver mediaUrlResolver;
	private final Clock clock;

	// 구매한 상품의 리뷰를 작성하고 상품의 리뷰 집계에 더한다.
	@Transactional
	public Long create(Long memberId, Long productId, ProductReviewCreateCommand command) {
		// 구매 후 상품이 숨겨질 수 있어 노출 여부는 보지 않는다
		productRepository.findByIdAndDeletedAtIsNull(productId).orElseThrow(
				() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND)
		);
		if (!productOptionRepository.existsByIdAndProductId(command.productOptionId(), productId)) {
			throw new RestApiException(ProductErrorCode.OPTION_NOT_FOUND);
		}
		// TODO(논의 필요): 지금은 클라이언트가 보낸 주문 정보를 그대로 믿어 남의 주문이나 없는 주문으로도 리뷰를 쓸 수 있다.
		//  order 모듈에 리뷰 가능한 내 구매 건 조회 API(memberId, productId)를 요청해 두었다.
		//  API가 생기면 orderItemId가 그 목록에 있는지 확인하고, orderId와 productOptionId는 요청 대신 목록에서 꺼내 저장한다.
		validateNotReviewed(command.orderId(), productId);

		ProductReview review = ProductReview.create(
				productId, command.productOptionId(), command.orderId(), command.orderItemId(),
				memberId, command.rating(), command.content(), toMediaSpecs(command.mediaUrls())
		);
		// 집계를 INSERT보다 먼저 더해 상품 행에 배타 락을 먼저 잡는다.
		// INSERT를 먼저 하면 FK 때문에 상품 행에 공유 락이 걸리고, 같은 상품에 동시에 쓰면 서로 배타 락을 기다리다 데드락이 난다.
		// 저장이 실패하면 트랜잭션이 롤백되어 더한 집계도 되돌아간다
		productRepository.addReviewStats(productId, 1, command.rating());
		return saveReview(review);
	}

	@Transactional
	public void update(Long memberId, Long reviewId, ProductReviewUpdateCommand command) {
		ProductReview review = findOwnedReview(memberId, reviewId);
		int previousRating = review.getRating();

		review.update(command.rating(), command.content(), toMediaSpecs(command.mediaUrls()));
		applyRatingChange(review.getProductId(), previousRating, command.rating());
	}

	// 내 리뷰를 삭제하고 상품의 리뷰 집계에서 뺀다
	@Transactional
	public void delete(Long memberId, Long reviewId) {
		// 리뷰 행을 잠그고 삭제한 리뷰는 조회하지 않아, 동시에 두 번 지워도 집계가 두 번 빠지지 않는다
		ProductReview review = findOwnedReview(memberId, reviewId);
		review.delete(now());
		productRepository.addReviewStats(review.getProductId(), -1, -review.getRating());
	}

	// 구매자에게 보이는 상품의 리뷰를 최신순으로 조회한다
	public Page<ProductReviewResult> getProductReviews(Long productId, Pageable pageable) {
		// 숨긴 상품의 리뷰가 상품 상세보다 먼저 새어 나가지 않게 상세와 같은 조건으로 막는다
		productRepository.findByIdAndVisibilityAndDeletedAtIsNull(productId, ProductVisibility.VISIBLE)
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
		return toResults(productReviewRepository.findByProductIdAndDeletedAtIsNullOrderByIdDesc(productId, pageable));
	}

	// 내가 쓴 리뷰를 최신순으로 조회한다. 상품이 숨겨지거나 지워져도 내 리뷰는 보여 준다
	public Page<ProductReviewResult> getMyReviews(Long memberId, Pageable pageable) {
		return toResults(productReviewRepository.findByMemberIdAndDeletedAtIsNullOrderByIdDesc(memberId, pageable));
	}

	// 상품 상세 상단에 띄울 이 상품의 내 리뷰. 재구매하면 여러 건이다
	public List<ProductReviewResult> getMyReviewsOfProduct(Long memberId, Long productId) {
		List<ProductReview> reviews =
			productReviewRepository.findByProductIdAndMemberIdAndDeletedAtIsNullOrderByIdDesc(productId, memberId);
		Map<Long, String> optionNames = findOptionNames(reviews);
		return reviews.stream()
			.map(review -> toResult(review, optionNames))
			.toList();
	}

	// 삭제한 리뷰도 포함해서 본다. 지우고 다시 써서 같은 주문으로 리뷰를 여러 번 남기지 못하게 한다
	private void validateNotReviewed(Long orderId, Long productId) {
		if (productReviewRepository.existsByOrderIdAndProductId(orderId, productId)) {
			throw new RestApiException(ProductReviewErrorCode.DUPLICATE_REVIEW);
		}
	}

	// 동시에 중복 검사를 통과한 요청은 UNIQUE 위반이 나서 중복으로 바꾼다
	private Long saveReview(ProductReview review) {
		try {
			return productReviewRepository.saveAndFlush(review).getId();
		} catch (DataIntegrityViolationException e) {
			throw new RestApiException(ProductReviewErrorCode.DUPLICATE_REVIEW);
		}
	}

	// 개수는 그대로 두고 별점 차이만 합계에 반영한다
	private void applyRatingChange(Long productId, int previousRating, int newRating) {
		if (previousRating == newRating) {
			return;
		}
		productRepository.addReviewStats(productId, 0, (newRating - previousRating));
	}

	// 수정·삭제 전에 리뷰를 가져오고 내 리뷰인지 확인한다
	private ProductReview findOwnedReview(Long memberId, Long reviewId) {
		ProductReview review = productReviewRepository.findForUpdateByIdAndDeletedAtIsNull(reviewId)
			.orElseThrow(() -> new RestApiException(ProductReviewErrorCode.REVIEW_NOT_FOUND));
		if (!review.getMemberId().equals(memberId)) {
			throw new RestApiException(ProductReviewErrorCode.REVIEW_ACCESS_DENIED);
		}
		return review;
	}

	private List<ProductReviewMediaSpec> toMediaSpecs(List<String> mediaUrls) {
		return mediaUrls.stream()
			.map(mediaValidator::toSpec)
			.toList();
	}

	private Page<ProductReviewResult> toResults(Page<ProductReview> reviews) {
		Map<Long, String> optionNames = findOptionNames(reviews.getContent());
		return reviews.map(review -> toResult(review, optionNames));
	}

	// 리뷰마다 옵션을 조회하지 않도록 목록의 옵션 이름을 한 번에 가져온다
	private Map<Long, String> findOptionNames(List<ProductReview> reviews) {
		List<Long> optionIds = reviews.stream()
			.map(ProductReview::getProductOptionId)
			.distinct()
			.toList();
		return productOptionRepository.findAllById(optionIds).stream()
			.collect(Collectors.toMap(ProductOption::getId, ProductOption::getName));
	}

	private ProductReviewResult toResult(ProductReview review, Map<Long, String> optionNames) {
		List<ProductReviewMediaResult> media = review.getMedia().stream()
			.map(this::toMediaResult)
			.toList();
		return new ProductReviewResult(review.getId(), review.getProductId(), review.getProductOptionId(),
			optionNames.get(review.getProductOptionId()), review.getMemberId(), review.getRating(), review.getContent(),
			media, review.getCreatedAt(), review.getUpdatedAt());
	}

	private ProductReviewMediaResult toMediaResult(ProductReviewMedia media) {
		return new ProductReviewMediaResult(mediaUrlResolver.toUrl(media.getObjectKey()), media.getMediaType().name());
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
