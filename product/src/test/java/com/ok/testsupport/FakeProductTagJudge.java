package com.ok.testsupport;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.ok.product.product.application.dto.ProductTagInput;
import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.application.port.out.ProductTagJudge;
import com.ok.product.product.application.service.ProductTagList;
import com.ok.product.product.exception.ProductTagJudgeFailedException;
import com.ok.product.product.exception.ProductTagJudgeUnavailableException;

/**
 * 외부 태그 판정 서비스 대신 상품명마다 넣어 둔 확률을 돌려주는 Fake.
 * {@link FakeReviewKeywordAnalyzer}처럼 {@code @ComponentScan}이 주워 가고, {@code @Primary}로 운영 판정기를 대신한다.
 * 실제 판정기처럼 태그 목록의 모든 태그에 확률을 돌려준다. 넣지 않은 태그는 0이다.
 * 테스트가 태그 이름으로 {@link #respond}하거나 {@link #fail}, {@link #failRetryable}, {@link #failUnavailable}로 상품명별 결과를 넣고, 끝나면 {@link #clear}로 비운다.
 */
@Component
@Primary
public class FakeProductTagJudge implements ProductTagJudge {

	public static final String MODEL = "fake-tag-model";

	private final ProductTagList tagList;
	private final Map<String, Map<String, Double>> probabilities = new ConcurrentHashMap<>();
	private final Set<String> failures = ConcurrentHashMap.newKeySet();
	private final Set<String> retryableFailures = ConcurrentHashMap.newKeySet();
	private final Set<String> unavailableFailures = ConcurrentHashMap.newKeySet();
	private final List<String> judgedNames = new CopyOnWriteArrayList<>();

	public FakeProductTagJudge(ProductTagList tagList) {
		this.tagList = tagList;
	}

	// 태그 이름("아이 간식")으로 넣는다. 목록에 없는 이름은 테스트 실수라 바로 알린다
	public void respond(String productName, Map<String, Double> probabilityByLabel) {
		Map<String, String> idByLabel = tagList.tags().stream()
			.collect(Collectors.toMap(ProductTagList.Tag::label, ProductTagList.Tag::id));
		probabilities.put(productName, probabilityByLabel.entrySet().stream()
			.collect(Collectors.toMap(entry -> {
				String id = idByLabel.get(entry.getKey());
				if (id == null) {
					throw new IllegalArgumentException("태그 목록에 없는 이름입니다. label=" + entry.getKey());
				}
				return id;
			}, Map.Entry::getValue)));
	}

	public void fail(String productName) {
		failures.add(productName);
	}

	public void failRetryable(String productName) {
		retryableFailures.add(productName);
	}

	// 인증 거절처럼 판정기를 쓸 수 없는 실패다
	public void failUnavailable(String productName) {
		unavailableFailures.add(productName);
	}

	// 판정기를 부르지 않아야 하는 상품(삭제)을 확인하려고 판정한 상품명을 남긴다
	public List<String> judgedNames() {
		return List.copyOf(judgedNames);
	}

	public void clear() {
		probabilities.clear();
		failures.clear();
		retryableFailures.clear();
		unavailableFailures.clear();
		judgedNames.clear();
	}

	@Override
	public ProductTagScores judge(ProductTagInput input) {
		String name = input.productName();
		judgedNames.add(name);
		if (failures.contains(name)) {
			throw ProductTagJudgeFailedException.finalFailure("Fake 태그 판정 실패", null);
		}
		if (unavailableFailures.contains(name)) {
			throw new ProductTagJudgeUnavailableException("Fake 태그 판정기 인증 거절", null);
		}
		if (retryableFailures.contains(name)) {
			throw ProductTagJudgeFailedException.retryable("Fake 태그 판정 일시 오류", null);
		}
		Map<String, Double> configured = probabilities.get(name);
		if (configured == null) {
			throw new IllegalStateException("Fake에 결과를 넣지 않은 상품입니다. productName=" + name);
		}
		Map<String, Double> all = tagList.tags().stream()
			.collect(Collectors.toMap(ProductTagList.Tag::id, tag -> configured.getOrDefault(tag.id(), 0.0)));
		return new ProductTagScores(all, tagList.version(), MODEL);
	}
}
