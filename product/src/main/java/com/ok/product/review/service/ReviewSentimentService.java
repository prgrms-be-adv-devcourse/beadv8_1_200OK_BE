package com.ok.product.review.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

/**
 * 리뷰 감성 분석 요청을 꺼내고 결과를 저장하며, 판매자에게 상품의 감성 집계를 보여 준다.
 * 분석기 호출은 트랜잭션 밖({@link ReviewSentimentAnalysisFacade})에서 하고, 여기서는 DB 작업만 짧은 트랜잭션으로 한다.
 */
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
	 */
	public List<ReviewSentimentTarget> findPendingTargets(int limit) {
		List<ReviewAnalysisRequest> requests = analysisRequestRepository
			.findByAnalysisTypeAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
				ReviewAnalysisType.SENTIMENT, ReviewAnalysisStatus.PENDING, now(), Limit.of(limit));
		Map<Long, ProductReview> reviews = productReviewRepository.findAllById(
				requests.stream().map(ReviewAnalysisRequest::getReviewId).toList()).stream()
			.collect(Collectors.toMap(ProductReview::getId, Function.identity()));
		// 리뷰마다 상품·옵션을 조회하지 않도록 이름을 한 번에 가져온다
		Map<Long, String> productNames = productRepository.findAllById(
				reviews.values().stream().map(ProductReview::getProductId).distinct().toList()).stream()
			.collect(Collectors.toMap(Product::getId, Product::getName));
		Map<Long, String> optionNames = productOptionRepository.findAllById(
				reviews.values().stream().map(ProductReview::getProductOptionId).distinct().toList()).stream()
			.collect(Collectors.toMap(ProductOption::getId, ProductOption::getName));

		return requests.stream()
			.map(request -> {
				ProductReview review = reviews.get(request.getReviewId());
				ReviewSentimentInput input = new ReviewSentimentInput(review.getId(),
					productNames.get(review.getProductId()), optionNames.get(review.getProductOptionId()), review.getContent());
				return new ReviewSentimentTarget(request.getId(), review.getUpdatedAt(), input);
			})
			.toList();
	}

	/**
	 * 분석 결과를 저장하고 요청을 완료한다.
	 *
	 * @throws RestApiException {@code ANALYSIS_REQUEST_NOT_PENDING} 다른 작업자가 먼저 끝낸 요청이면
	 */
	@Transactional
	public void complete(ReviewSentimentTarget target, ReviewSentimentAnalysis analysis) {
		ReviewAnalysisRequest request = findRequest(target.requestId());
		request.complete();
		reviewSentimentRepository.save(ReviewSentiment.create(
			request.getReviewId(), target.reviewUpdatedAt(), analysis.overall(), analysis.aspects(),
			analysis.criteriaVersion(), analysis.model(), now()));
	}

	/**
	 * 요청을 최종 실패로 끝낸다.
	 *
	 * @throws RestApiException {@code ANALYSIS_REQUEST_NOT_PENDING} 다른 작업자가 먼저 끝낸 요청이면
	 */
	@Transactional
	public void fail(Long requestId, String error) {
		findRequest(requestId).fail(error);
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

	private ReviewAnalysisRequest findRequest(Long requestId) {
		return analysisRequestRepository.findById(requestId)
			.orElseThrow(() -> new IllegalStateException("리뷰 분석 요청이 없습니다. requestId=" + requestId));
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
