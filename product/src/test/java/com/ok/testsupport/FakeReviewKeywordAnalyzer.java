package com.ok.testsupport;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewAnalyzerUnavailableException;
import com.ok.product.review.client.ReviewKeywordAnalyzer;
import com.ok.product.review.client.ReviewKeywordList;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordInput;

/**
 * 외부 키워드 분석 서비스 대신 리뷰 본문마다 넣어 둔 확률을 돌려주는 Fake.
 * {@link FakeReviewSentimentAnalyzer}처럼 {@code @ComponentScan}이 주워 가고, {@code @Primary}로 운영 분석기를 대신한다.
 * 실제 분석기처럼 리뷰 상품의 분류에 묻는 키워드마다 확률을 돌려준다. 넣지 않은 키워드는 0이고, 그 분류에 묻지 않는 키워드는 넣어도 돌려주지 않는다.
 * 테스트가 {@link #respond}, {@link #fail}, {@link #failRetryable}, {@link #failUnavailable}로 본문별 결과를 넣고, 끝나면 {@link #clear}로 비운다.
 */
@Component
@Primary
public class FakeReviewKeywordAnalyzer implements ReviewKeywordAnalyzer {

	public static final String MODEL = "fake-keyword-model";

	private final ReviewKeywordList keywordList;
	private final Map<String, Map<String, Double>> probabilities = new ConcurrentHashMap<>();
	private final Set<String> failures = ConcurrentHashMap.newKeySet();
	private final Set<String> retryableFailures = ConcurrentHashMap.newKeySet();
	private final Set<String> unavailableFailures = ConcurrentHashMap.newKeySet();
	private final List<String> analyzedContents = new CopyOnWriteArrayList<>();

	public FakeReviewKeywordAnalyzer(ReviewKeywordList keywordList) {
		this.keywordList = keywordList;
	}

	public void respond(String content, Map<String, Double> keywordProbabilities) {
		probabilities.put(content, Map.copyOf(keywordProbabilities));
	}

	public void fail(String content) {
		failures.add(content);
	}

	public void failRetryable(String content) {
		retryableFailures.add(content);
	}

	// 인증 거절처럼 분석기를 쓸 수 없는 실패다
	public void failUnavailable(String content) {
		unavailableFailures.add(content);
	}

	// 분석기를 부르지 않아야 하는 리뷰(삭제·내용 없음)를 확인하려고 분석한 본문을 남긴다
	public List<String> analyzedContents() {
		return List.copyOf(analyzedContents);
	}

	public void clear() {
		probabilities.clear();
		failures.clear();
		retryableFailures.clear();
		unavailableFailures.clear();
		analyzedContents.clear();
	}

	@Override
	public ReviewKeywordAnalysis analyze(ReviewKeywordInput input) {
		analyzedContents.add(input.content());
		if (failures.contains(input.content())) {
			throw ReviewAnalysisFailedException.finalFailure("Fake 키워드 분석 실패", null);
		}
		if (unavailableFailures.contains(input.content())) {
			throw new ReviewAnalyzerUnavailableException("Fake 키워드 분석기 인증 거절", null);
		}
		if (retryableFailures.contains(input.content())) {
			throw ReviewAnalysisFailedException.retryable("Fake 키워드 일시 오류", null);
		}
		Map<String, Double> configured = probabilities.get(input.content());
		if (configured == null) {
			throw new IllegalStateException("Fake에 결과를 넣지 않은 리뷰입니다. content=" + input.content());
		}
		Map<String, Double> asked = keywordList.keywordsFor(input.category()).stream()
			.collect(Collectors.toMap(ReviewKeywordList.Keyword::id, keyword -> configured.getOrDefault(keyword.id(), 0.0)));
		return new ReviewKeywordAnalysis(asked, keywordList.version(), MODEL);
	}
}
