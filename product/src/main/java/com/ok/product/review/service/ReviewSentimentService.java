package com.ok.product.review.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ReviewAnalysisRequest;
import com.ok.product.review.domain.ReviewAnalysisStatus;
import com.ok.product.review.domain.ReviewAnalysisType;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.ReviewSentiment;
import com.ok.product.review.domain.Sentiment;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.repository.ReviewAnalysisRequestRepository;
import com.ok.product.review.repository.ReviewAspectSentimentCount;
import com.ok.product.review.repository.ReviewSentimentCount;
import com.ok.product.review.repository.ReviewSentimentRepository;
import com.ok.product.review.service.dto.AspectSentimentSummaryResult;
import com.ok.product.review.service.dto.ReviewSentimentAnalysis;
import com.ok.product.review.service.dto.ReviewSentimentInput;
import com.ok.product.review.service.dto.ReviewSentimentSummaryResult;
import com.ok.product.review.service.dto.ReviewSentimentTarget;
import com.ok.product.review.service.dto.SentimentCountsResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 리뷰 감성 분석 요청을 꺼내고 결과를 저장하며, 판매자에게 상품의 감성 집계를 보여 준다.
 * 분석기 호출은 트랜잭션 밖({@link ReviewSentimentAnalysisFacade})에서 하고, 여기서는 DB 작업만 짧은 트랜잭션으로 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewSentimentService {

	private final ReviewAnalysisRequestRepository analysisRequestRepository;
	private final ReviewSentimentRepository reviewSentimentRepository;
	private final ProductReviewRepository productReviewRepository;
	private final ProductRepository productRepository;
	private final ProductOptionRepository productOptionRepository;
	private final Clock clock;

	/**
	 * 다음 시도 시각이 지난 감성 분석 대기 요청을 오래 기다린 순서로 꺼내 분석기에 넘길 리뷰 내용과 함께 돌려준다.
	 * 리뷰가 삭제된 요청은 취소하고, 내용 없는 리뷰의 요청은 분석 안 함으로 끝내 분석기에 넘기지 않는다.
	 */
	@Transactional
	public List<ReviewSentimentTarget> findPendingTargets(int limit) {
		List<ReviewAnalysisRequest> requests = analysisRequestRepository
			.findByAnalysisTypeAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
				ReviewAnalysisType.SENTIMENT, ReviewAnalysisStatus.PENDING, now(), Limit.of(limit));
		Map<Long, ProductReview> reviews = productReviewRepository.findAllById(
				requests.stream().map(ReviewAnalysisRequest::getReviewId).toList()).stream()
			.collect(Collectors.toMap(ProductReview::getId, Function.identity()));

		List<ReviewAnalysisRequest> analyzable = new ArrayList<>();
		for (ReviewAnalysisRequest request : requests) {
			ProductReview review = reviews.get(request.getReviewId());
			// 리뷰를 삭제할 때는 요청을 건드리지 않으므로 여기서 끝낸다
			if (review.isDeleted()) {
				request.cancel();
			} else if (!review.hasAnalyzableContent()) {
				request.skip();
			} else {
				analyzable.add(request);
			}
		}

		// 리뷰마다 상품·옵션을 조회하지 않도록 이름을 한 번에 가져온다
		Map<Long, String> productNames = productRepository.findAllById(
				reviews.values().stream().map(ProductReview::getProductId).distinct().toList()).stream()
			.collect(Collectors.toMap(Product::getId, Product::getName));
		Map<Long, String> optionNames = productOptionRepository.findAllById(
				reviews.values().stream().map(ProductReview::getProductOptionId).distinct().toList()).stream()
			.collect(Collectors.toMap(ProductOption::getId, ProductOption::getName));

		return analyzable.stream()
			.map(request -> {
				ProductReview review = reviews.get(request.getReviewId());
				ReviewSentimentInput input = new ReviewSentimentInput(review.getId(),
					productNames.get(review.getProductId()), optionNames.get(review.getProductOptionId()), review.getContent());
				return new ReviewSentimentTarget(request.getId(), review.getUpdatedAt(), input);
			})
			.toList();
	}

	/**
	 * 분석 결과를 저장하고 요청을 완료한다. 분석하는 동안 리뷰가 수정됐으면 결과를 버리고, 삭제됐으면 요청을 취소한다.
	 *
	 * @throws RestApiException {@code ANALYSIS_REQUEST_NOT_PENDING} 다른 작업자가 먼저 끝낸 요청이면
	 */
	@Transactional
	public void complete(ReviewSentimentTarget target, ReviewSentimentAnalysis analysis) {
		if (!confirmReviewUnchanged(target)) {
			return;
		}
		ReviewAnalysisRequest request = findRequest(target.requestId());
		request.complete();
		reviewSentimentRepository.findByReviewId(request.getReviewId()).ifPresentOrElse(
			sentiment -> sentiment.replace(target.reviewUpdatedAt(), analysis.overall(), analysis.aspects(),
				analysis.criteriaVersion(), analysis.model(), now()),
			() -> reviewSentimentRepository.save(ReviewSentiment.create(
				request.getReviewId(), target.reviewUpdatedAt(), analysis.overall(), analysis.aspects(),
				analysis.criteriaVersion(), analysis.model(), now())));
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
	public void fail(ReviewSentimentTarget target, RuntimeException cause, boolean retryable) {
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
			log.error("리뷰 감성 분석 최종 실패. requestId={}, reviewId={}, attemptCount={}",
				request.getId(), request.getReviewId(), request.getAttemptCount(), cause);
		} else {
			// 다시 시도해 회복될 수 있어 스택 트레이스 없이 남긴다
			log.warn("리뷰 감성 분석 실패, 나중에 다시 시도. requestId={}, reviewId={}, attemptCount={}, nextAttemptAt={}, error={}",
				request.getId(), request.getReviewId(), request.getAttemptCount(), request.getNextAttemptAt(), cause.toString());
		}
	}

	/**
	 * 판매자 상품의 분석 완료 리뷰를 전체 감성과 분석 항목별로 센다.
	 * 분석을 기다리거나 최종 실패한 리뷰는 결과가 없어 분모와 분자 어디에도 들지 않는다.
	 *
	 * @throws RestApiException {@code PRODUCT_NOT_FOUND} 상품이 없거나 삭제됐거나 내 상품이 아니면
	 */
	public ReviewSentimentSummaryResult getSummary(Long sellerId, Long productId) {
		// 판매자 상품 상세(403)와 달리 다른 판매자의 상품도 없는 상품과 같은 404로 응답한다 (#386)
		productRepository.findByIdAndDeletedAtIsNull(productId)
			.filter(product -> product.isOwnedBy(sellerId))
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));

		Map<Sentiment, Long> overallCounts = reviewSentimentRepository.countOverallByProductId(productId).stream()
			.collect(Collectors.toMap(ReviewSentimentCount::sentiment, ReviewSentimentCount::count));
		Map<ReviewAspect, Map<Sentiment, Long>> aspectCounts = new EnumMap<>(ReviewAspect.class);
		for (ReviewAspectSentimentCount count : reviewSentimentRepository.countAspectsByProductId(productId)) {
			aspectCounts.computeIfAbsent(count.aspect(), aspect -> new EnumMap<>(Sentiment.class))
				.put(count.sentiment(), count.count());
		}

		SentimentCountsResult overall = SentimentCountsResult.from(overallCounts);
		List<AspectSentimentSummaryResult> aspects = Arrays.stream(ReviewAspect.values())
			.map(aspect -> {
				SentimentCountsResult counts = SentimentCountsResult.from(aspectCounts.getOrDefault(aspect, Map.of()));
				return new AspectSentimentSummaryResult(aspect.name(), counts.total(), counts);
			})
			.toList();
		return new ReviewSentimentSummaryResult(overall.total(), overall, aspects);
	}

	// 분석한 내용이 아직 리뷰의 현재 내용인지 리뷰 행을 잠그고 확인한다. 리뷰가 삭제됐으면 요청을 취소한다.
	// 잠그지 않으면 수정이 요청을 대기로 되돌린 뒤 이 트랜잭션이 요청을 완료로 덮어써, 새 내용이 분석되지 않는다.
	// 요청은 잠금을 잡은 뒤에 읽어야 수정이 되돌린 상태를 본다
	private boolean confirmReviewUnchanged(ReviewSentimentTarget target) {
		Long reviewId = target.input().reviewId();
		Optional<ProductReview> review = productReviewRepository.findForUpdateByIdAndDeletedAtIsNull(reviewId);
		if (review.isEmpty()) {
			// soft delete라 행은 남아 있다. 조회되지 않으면 분석하는 동안 삭제된 리뷰다
			findRequest(target.requestId()).cancel();
			return false;
		}
		if (!review.get().getUpdatedAt().equals(target.reviewUpdatedAt())) {
			// 리뷰 수정이 요청을 이미 대기로 되돌렸으므로 요청은 그대로 두고 다음 실행에서 새 내용으로 분석한다
			log.info("분석하는 동안 리뷰가 수정되어 결과를 버림. requestId={}, reviewId={}", target.requestId(), reviewId);
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
