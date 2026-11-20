package com.ok.product.product.application.dto;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 태그 판정기가 돌려준 상품 하나의 태그별 "해당" 확률과, 어느 태그를 붙일지 정하는 판정이다.
 * 판정을 판정기가 아니라 여기 두어, 판정기를 바꿔도 같은 기준이 적용되게 한다.
 * 기준값 0.9는 평가셋 상품 300개로 정한 고정 설정이라 설정으로 빼지 않는다(#746, 리뷰 키워드와 같은 방식).
 *
 * @param probabilities 태그 ID마다 "해당"을 고를 확률
 * @param tagVersion    판정에 쓴 태그 목록(tags.json)의 {@code version}
 */
public record ProductTagScores(
	Map<String, Double> probabilities,
	String tagVersion,
	String model
) {

	public static final double APPLIES_THRESHOLD = 0.9;

	public ProductTagScores {
		if (probabilities == null || tagVersion == null || model == null) {
			throw new IllegalArgumentException("태그별 확률, 태그 목록 버전, 모델은 필수입니다.");
		}
		// containsKey(null)은 Map.of가 NPE를 던져 항목을 하나씩 본다
		if (probabilities.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null
			|| entry.getValue() < 0 || entry.getValue() > 1)) {
			throw new IllegalArgumentException("태그별 확률은 0 이상 1 이하여야 합니다. probabilities=" + probabilities);
		}
		probabilities = Map.copyOf(probabilities);
	}

	public Set<String> appliedTagIds() {
		return probabilities.entrySet().stream()
			.filter(entry -> entry.getValue() >= APPLIES_THRESHOLD)
			.map(Map.Entry::getKey)
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * 확률이 가장 높은 태그다. 같으면 ID가 앞선 태그로 정해 다시 판정해도 같은 값이 남게 한다. 물은 태그가 없으면 빈 값이다.
	 */
	public Optional<Map.Entry<String, Double>> topTag() {
		return probabilities.entrySet().stream()
			.max(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
				.thenComparing(Map.Entry::getKey, Comparator.reverseOrder()));
	}
}
