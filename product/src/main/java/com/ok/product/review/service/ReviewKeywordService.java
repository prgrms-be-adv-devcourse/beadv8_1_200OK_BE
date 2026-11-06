package com.ok.product.review.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.review.client.ReviewKeywordList;
import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ReviewAnalysisRequest;
import com.ok.product.review.domain.ReviewAnalysisStatus;
import com.ok.product.review.domain.ReviewAnalysisType;
import com.ok.product.review.domain.ReviewKeyword;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.repository.ReviewAnalysisRequestRepository;
import com.ok.product.review.repository.ReviewKeywordCount;
import com.ok.product.review.repository.ReviewKeywordRepository;
import com.ok.product.review.service.dto.ProductReviewKeywordsResult;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordCountResult;
import com.ok.product.review.service.dto.ReviewKeywordInput;
import com.ok.product.review.service.dto.ReviewKeywordTarget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 리뷰 키워드 분석 요청을 꺼내고 결과를 저장하며, 구매자에게 상품의 키워드 집계를 보여 준다.
 * 분석기 호출은 트랜잭션 밖({@link ReviewKeywordAnalysisFacade})에서 하고, 여기서는 DB 작업만 짧은 트랜잭션으로 한다.
 * 감성 분석({@link ReviewSentimentService})과 같은 규칙이지만, 한쪽 분석기가 멈춰도 다른 쪽이 돌도록 요청·결과·작업자를 따로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewKeywordService {

	// 분석 완료 리뷰가 이보다 적으면 몇 명의 말이 칩이 되어 대표성이 없다
	private static final int MIN_ANALYZED_REVIEWS = 10;
	// 한두 리뷰만 표현한 키워드는 칩으로 보여 주지 않는다
	private static final int MIN_EXPRESSED_REVIEWS = 3;
	private static final int MAX_KEYWORDS = 10;

	private final ReviewAnalysisRequestRepository analysisRequestRepository;
	private final ReviewKeywordRepository reviewKeywordRepository;
	private final ProductReviewRepository productReviewRepository;
	private final ProductRepository productRepository;
	private final ReviewKeywordList keywordList;
	private final Clock clock;

	/**
	 * 다음 시도 시각이 지난 키워드 분석 대기 요청을 오래 기다린 순서로 꺼내 분석기에 넘길 리뷰 내용과 함께 돌려준다.
	 * 리뷰나 상품이 없거나 리뷰가 삭제된 요청은 취소하고, 내용 없는 리뷰의 요청은 분석 안 함으로 끝내 분석기에 넘기지 않는다.
	 * 넘기는 요청은 다음 시도 시각을 미뤄, 앱이 여러 대여도 다른 작업자가 같은 요청을 꺼내 분석기를 두 번 부르지 않게 한다.
	 */
	@Transactional
	public List<ReviewKeywordTarget> findPendingTargets(int limit) {
		LocalDateTime now = now();
		List<ReviewAnalysisRequest> requests = analysisRequestRepository
			.findByAnalysisTypeAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
				ReviewAnalysisType.KEYWORD, ReviewAnalysisStatus.PENDING, now, Limit.of(limit));
		Map<Long, ProductReview> reviews = productReviewRepository.findAllById(
				requests.stream().map(ReviewAnalysisRequest::getReviewId).toList()).stream()
			.collect(Collectors.toMap(ProductReview::getId, Function.identity()));
		// 리뷰마다 상품을 조회하지 않도록 한 번에 가져온다
		Map<Long, Product> products = productRepository.findAllById(
				reviews.values().stream().map(ProductReview::getProductId).distinct().toList()).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));

		List<ReviewKeywordTarget> targets = new ArrayList<>();
		for (ReviewAnalysisRequest request : requests) {
			ProductReview review = reviews.get(request.getReviewId());
			// 리뷰를 삭제할 때는 요청을 건드리지 않으므로 여기서 끝낸다.
			// 운영 DB에서 외래키 검사를 끄고 리뷰나 상품 행을 직접 지우면 행이 없다. 상품이 없으면 물을 키워드를 정할 분류도 없다.
			// 그대로 두면 이 요청이 매번 맨 앞에서 꺼내져 다른 요청까지 막힌다
			Product product = review == null ? null : products.get(review.getProductId());
			if (review == null || review.isDeleted() || product == null) {
				request.cancel();
			} else if (!review.hasAnalyzableContent()) {
				request.skip();
			} else {
				request.claim(now);
				// 상품의 현재 분류로 묻는다. 분류가 바뀌어도 이미 분석한 리뷰는 다시 묻지 않는다(#422)
				ReviewKeywordInput input = new ReviewKeywordInput(
					review.getId(), product.getName(), review.getContent(), product.getCategory());
				targets.add(new ReviewKeywordTarget(request.getId(), review.getId(), review.getUpdatedAt(), input));
			}
		}
		return targets;
	}

	/**
	 * 분석 결과를 판정해 표현한 키워드를 저장하고 요청을 완료한다. 분석하는 동안 리뷰가 수정됐으면 결과를 버리고, 삭제됐으면 요청을 취소한다.
	 *
	 * @throws RestApiException {@code ANALYSIS_REQUEST_NOT_PENDING} 다른 작업자가 먼저 끝낸 요청이면
	 */
	@Transactional
	public void complete(ReviewKeywordTarget target, ReviewKeywordAnalysis analysis) {
		if (!confirmReviewUnchanged(target)) {
			return;
		}
		ReviewAnalysisRequest request = findRequest(target.requestId());
		request.complete();
		Set<String> expressedKeywordIds = analysis.expressedKeywordIds();
		reviewKeywordRepository.findByReviewId(request.getReviewId()).ifPresentOrElse(
			reviewKeyword -> reviewKeyword.replace(target.reviewUpdatedAt(), expressedKeywordIds,
				analysis.keywordVersion(), analysis.model(), now()),
			() -> reviewKeywordRepository.save(ReviewKeyword.create(
				request.getReviewId(), target.reviewUpdatedAt(), expressedKeywordIds,
				analysis.keywordVersion(), analysis.model(), now())));
	}

	/**
	 * 분석 실패를 기록한다. 재시도할 실패면 다음 시도 시각을 미루고, 아니면 바로 최종 실패로 끝낸다.
	 * 분석하는 동안 리뷰가 수정됐으면 수정 전 내용의 실패라 세지 않고, 삭제됐으면 요청을 취소한다.
	 * 최종 실패는 운영자가 SQL로 되돌려야 하므로 ERROR 로그를 남긴다.
	 *
	 * @param cause 분석기가 던진 예외. 마지막 오류와 로그에 남긴다
	 * @throws RestApiException {@code ANALYSIS_REQUEST_NOT_PENDING} 다른 작업자가 먼저 끝낸 요청이면
	 */
	@Transactional
	public void fail(ReviewKeywordTarget target, RuntimeException cause, boolean retryable) {
		if (!confirmReviewUnchanged(target)) {
			return;
		}
		ReviewAnalysisRequest request = findRequest(target.requestId());
		if (retryable) {
			request.failRetryable(cause.toString(), now());
		} else {
			request.fail(cause.toString());
		}

		if (request.getStatus() == ReviewAnalysisStatus.FAILED) {
			log.error("리뷰 키워드 분석 최종 실패. requestId={}, reviewId={}, attemptCount={}",
				request.getId(), request.getReviewId(), request.getAttemptCount(), cause);
		} else {
			// 다시 시도해 회복될 수 있어 스택 트레이스 없이 남긴다
			log.warn("리뷰 키워드 분석 실패, 나중에 다시 시도. requestId={}, reviewId={}, attemptCount={}, nextAttemptAt={}, error={}",
				request.getId(), request.getReviewId(), request.getAttemptCount(), request.getNextAttemptAt(), cause.toString());
		}
	}

	/**
	 * 구매자에게 보이는 상품의 리뷰 키워드를 집계한다.
	 * 분석 완료 리뷰가 10개 이상일 때만 리뷰 3개 이상이 표현한 키워드를 많은 순으로 최대 10개 고르고, 못 미치면 키워드는 비운다.
	 * 현재 목록에 있고 상품의 현재 분류에 묻는 키워드만 센다. 문구를 고치거나 키워드를 빼면 이미 분석한 리뷰에도 바로 반영된다.
	 *
	 * @throws RestApiException {@code PRODUCT_NOT_FOUND} 상품이 없거나 숨겨졌거나 삭제됐으면
	 */
	public ProductReviewKeywordsResult getKeywords(Long productId) {
		// 숨긴 상품의 리뷰 내용이 상품 상세보다 먼저 새어 나가지 않게 리뷰 목록과 같은 조건으로 막는다
		Product product = productRepository.findByIdAndVisibilityAndDeletedAtIsNull(productId, ProductVisibility.VISIBLE)
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));

		long analyzedReviewCount = reviewKeywordRepository.countAnalyzedByProductId(productId);
		if (analyzedReviewCount < MIN_ANALYZED_REVIEWS) {
			return new ProductReviewKeywordsResult(analyzedReviewCount, List.of());
		}
		List<ReviewKeywordCountResult> keywords = reviewKeywordRepository.countExpressedByProductId(productId).stream()
			.filter(count -> count.count() >= MIN_EXPRESSED_REVIEWS)
			.flatMap(count -> keywordList.find(count.keywordId())
				.filter(keyword -> keyword.appliesTo(product.getCategory()))
				.map(keyword -> new ReviewKeywordCountResult(
					keyword.id(), keyword.phrase(), keyword.aspect().name(), count.count()))
				.stream())
			.sorted(Comparator.comparingLong(ReviewKeywordCountResult::reviewCount).reversed()
				.thenComparing(ReviewKeywordCountResult::keywordId))
			.limit(MAX_KEYWORDS)
			.toList();
		return new ProductReviewKeywordsResult(analyzedReviewCount, keywords);
	}

	// 분석한 내용이 아직 리뷰의 현재 내용인지 리뷰 행을 잠그고 확인한다. 리뷰가 삭제됐으면 요청을 취소한다.
	// 잠그지 않으면 수정이 요청을 대기로 되돌린 뒤 이 트랜잭션이 요청을 완료로 덮어써, 새 내용이 분석되지 않는다.
	// 요청은 잠금을 잡은 뒤에 읽어야 수정이 되돌린 상태를 본다
	private boolean confirmReviewUnchanged(ReviewKeywordTarget target) {
		Long reviewId = target.reviewId();
		Optional<ProductReview> review = productReviewRepository.findForUpdateByIdAndDeletedAtIsNull(reviewId);
		if (review.isEmpty()) {
			// soft delete라 행은 남아 있다. 조회되지 않으면 분석하는 동안 삭제된 리뷰다
			findRequest(target.requestId()).cancel();
			return false;
		}
		if (!review.get().getUpdatedAt().equals(target.reviewUpdatedAt())) {
			// 리뷰 수정이 요청을 이미 대기로 되돌렸으므로 요청은 그대로 두고 다음 실행에서 새 내용으로 분석한다
			log.info("키워드를 분석하는 동안 리뷰가 수정되어 결과를 버림. requestId={}, reviewId={}", target.requestId(), reviewId);
			return false;
		}
		return true;
	}

	private ReviewAnalysisRequest findRequest(Long requestId) {
		return analysisRequestRepository.findById(requestId)
			.orElseThrow(() -> new IllegalStateException("리뷰 분석 요청이 없습니다. requestId=" + requestId));
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
