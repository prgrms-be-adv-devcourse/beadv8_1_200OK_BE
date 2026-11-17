package com.ok.product.review.application.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.exception.ReviewAnalysisFailedException;
import com.ok.product.review.exception.ReviewAnalyzerUnavailableException;
import com.ok.product.review.application.port.out.ReviewKeywordAnalyzer;
import com.ok.product.review.application.service.ReviewKeywordList.Keyword;
import com.ok.product.review.application.port.out.ReviewSentimentAnalyzer;
import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.exception.ProductReviewErrorCode;
import com.ok.product.review.application.dto.ReviewAnalysisPreviewQuery;
import com.ok.product.review.application.dto.ReviewAnalysisSubjectResult;
import com.ok.product.review.application.dto.ReviewKeywordAnalysis;
import com.ok.product.review.application.dto.ReviewKeywordInput;
import com.ok.product.review.application.dto.ReviewKeywordJudgementResult;
import com.ok.product.review.application.dto.ReviewKeywordPreviewResult;
import com.ok.product.review.application.dto.ReviewSentimentAnalysis;
import com.ok.product.review.application.dto.ReviewSentimentInput;
import com.ok.product.review.application.dto.ReviewSentimentPreviewResult;
import com.ok.product.review.application.port.in.ReviewAnalysisPreviewUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자가 리뷰를 남기지 않고 본문 하나를 운영 작업자와 같은 입력과 판정으로 분석해 본다. 결과는 저장하지 않는다.
 * 분석기는 외부 서비스라 트랜잭션 밖(여기)에서 부르고, 상품·옵션 조회만 {@link ProductReviewService}의 읽기 트랜잭션으로 한다.
 * 키워드와 감성을 따로 부르게 두어, 한쪽 분석기가 실패해도 다른 쪽 결과는 받는다. 실패해도 다시 시도하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewAnalysisPreviewFacade implements ReviewAnalysisPreviewUseCase {

	private final ProductReviewService productReviewService;
	private final ReviewKeywordAnalyzer reviewKeywordAnalyzer;
	private final ReviewSentimentAnalyzer reviewSentimentAnalyzer;
	private final ReviewKeywordList keywordList;

	@Override
	public ReviewKeywordPreviewResult analyzeKeywords(ReviewAnalysisPreviewQuery query) {
		ReviewAnalysisSubjectResult subject = productReviewService.getAnalysisSubject(query.productId(), query.optionId());
		if (!ProductReview.isAnalyzableContent(query.content())) {
			return ReviewKeywordPreviewResult.notAnalyzed();
		}
		ReviewKeywordInput input = ReviewKeywordInput.seededByContent(subject.productName(), query.content(), subject.category());
		ReviewKeywordAnalysis analysis = callAnalyzer(() -> reviewKeywordAnalyzer.analyze(input));

		Set<String> expressed = analysis.expressedKeywordIds();
		Set<String> excludedByTextureRule = analysis.excludedByTextureRuleKeywordIds();
		List<ReviewKeywordJudgementResult> keywords = analysis.probabilities().entrySet().stream()
			.map(entry -> {
				Keyword keyword = keywordList.find(entry.getKey()).orElseThrow(() -> new IllegalStateException(
					"분석기가 키워드 목록에 없는 키워드를 돌려줬습니다. keywordId=" + entry.getKey()));
				return new ReviewKeywordJudgementResult(keyword.id(), keyword.phrase(), keyword.aspect().name(),
					entry.getValue(), expressed.contains(keyword.id()), excludedByTextureRule.contains(keyword.id()));
			})
			.sorted(Comparator.comparingDouble(ReviewKeywordJudgementResult::probability).reversed()
				.thenComparing(ReviewKeywordJudgementResult::keywordId))
			.toList();
		return new ReviewKeywordPreviewResult(true, analysis.keywordVersion(), analysis.model(), keywords);
	}

	@Override
	public ReviewSentimentPreviewResult analyzeSentiment(ReviewAnalysisPreviewQuery query) {
		ReviewAnalysisSubjectResult subject = productReviewService.getAnalysisSubject(query.productId(), query.optionId());
		if (!ProductReview.isAnalyzableContent(query.content())) {
			return ReviewSentimentPreviewResult.notAnalyzed();
		}
		ReviewSentimentInput input = ReviewSentimentInput.seededByContent(
			subject.productName(), subject.optionName(), query.content());
		ReviewSentimentAnalysis analysis = callAnalyzer(() -> reviewSentimentAnalyzer.analyze(input));

		Map<String, String> aspects = new LinkedHashMap<>();
		for (ReviewAspect aspect : ReviewAspect.values()) {
			aspects.put(aspect.name(), analysis.aspects().get(aspect).name());
		}
		return new ReviewSentimentPreviewResult(true, analysis.criteriaVersion(), analysis.model(),
			analysis.overall().name(), aspects);
	}

	// RestApiException에는 원인을 담을 자리가 없고 공통 처리기도 이 예외를 기록하지 않는다. 원인을 응답에 내보낼 수 없어 여기서 남긴다
	private static <T> T callAnalyzer(Supplier<T> analyze) {
		try {
			return analyze.get();
		} catch (ReviewAnalyzerUnavailableException e) {
			log.warn("관리자 리뷰 분석 테스트에서 분석기를 쓸 수 없음. error={}", e.toString());
			throw new RestApiException(ProductReviewErrorCode.REVIEW_ANALYZER_UNAVAILABLE);
		} catch (ReviewAnalysisFailedException e) {
			log.warn("관리자 리뷰 분석 테스트에서 분석 실패. error={}", e.toString());
			throw new RestApiException(ProductReviewErrorCode.REVIEW_ANALYSIS_FAILED);
		}
	}
}
