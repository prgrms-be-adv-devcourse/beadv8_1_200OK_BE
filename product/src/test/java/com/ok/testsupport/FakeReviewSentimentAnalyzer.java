package com.ok.testsupport;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewSentimentAnalyzer;
import com.ok.product.review.client.ReviewSentimentCriteria;
import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.Sentiment;
import com.ok.product.review.service.dto.ReviewSentimentAnalysis;
import com.ok.product.review.service.dto.ReviewSentimentInput;

/**
 * 외부 감성 분석 서비스 대신 리뷰 본문마다 넣어 둔 결과를 돌려주는 Fake.
 * {@link FakeOrderApiClient}처럼 {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고, {@code @Primary}로 운영 분석기를 대신한다.
 * 실제 분석기처럼 결과에 {@code product} 리소스에서 읽은 분류 기준 버전을 담는다.
 * 테스트가 {@link #respond}나 {@link #fail}로 본문별 결과를 넣고, 끝나면 {@link #clear}로 비운다.
 */
@Component
@Primary
public class FakeReviewSentimentAnalyzer implements ReviewSentimentAnalyzer {

	public static final String MODEL = "fake-sentiment-model";

	private final ReviewSentimentCriteria criteria;
	private final Map<String, ReviewSentimentAnalysis> results = new ConcurrentHashMap<>();
	private final Set<String> failures = ConcurrentHashMap.newKeySet();
	private final List<String> analyzedContents = new CopyOnWriteArrayList<>();

	public FakeReviewSentimentAnalyzer(ReviewSentimentCriteria criteria) {
		this.criteria = criteria;
	}

	// 넣지 않은 분석 항목은 언급 없음이다
	public void respond(String content, Sentiment overall, Map<ReviewAspect, AspectSentiment> mentionedAspects) {
		Map<ReviewAspect, AspectSentiment> aspects = new EnumMap<>(ReviewAspect.class);
		for (ReviewAspect aspect : ReviewAspect.values()) {
			aspects.put(aspect, mentionedAspects.getOrDefault(aspect, AspectSentiment.NOT_MENTIONED));
		}
		results.put(content, new ReviewSentimentAnalysis(overall, aspects, criteria.version(), MODEL));
	}

	public void fail(String content) {
		failures.add(content);
	}

	// 분석기를 부르지 않아야 하는 리뷰(삭제·내용 없음)를 확인하려고 분석한 본문을 남긴다
	public List<String> analyzedContents() {
		return List.copyOf(analyzedContents);
	}

	public void clear() {
		results.clear();
		failures.clear();
		analyzedContents.clear();
	}

	@Override
	public ReviewSentimentAnalysis analyze(ReviewSentimentInput input) {
		analyzedContents.add(input.content());
		if (failures.contains(input.content())) {
			throw ReviewAnalysisFailedException.finalFailure("Fake 분석 실패", null);
		}
		ReviewSentimentAnalysis result = results.get(input.content());
		if (result == null) {
			throw new IllegalStateException("Fake에 결과를 넣지 않은 리뷰입니다. content=" + input.content());
		}
		return result;
	}
}
