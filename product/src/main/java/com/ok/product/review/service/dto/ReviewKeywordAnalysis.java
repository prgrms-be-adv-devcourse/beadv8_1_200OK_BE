package com.ok.product.review.service.dto;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 키워드 분석기가 돌려준 리뷰 하나의 분석 결과와, 리뷰가 어떤 키워드를 표현했는지 정하는 판정이다.
 * 판정을 분석기가 아니라 여기 두어, 분석기를 바꿔도 같은 기준이 적용되게 한다.
 * 기준(0.9, 식감 규칙)은 평가에서 정밀도 0.942를 낸 고정 설정이라 설정으로 빼지 않는다(ADR #387).
 *
 * @param probabilities   물은 키워드마다 "말함"을 고를 확률. 키워드 ID가 키다
 * @param keywordVersion  분석에 쓴 키워드 목록(keywords.json)의 {@code version}
 */
public record ReviewKeywordAnalysis(
	Map<String, Double> probabilities,
	String keywordVersion,
	String model
) {

	public static final double EXPRESSED_THRESHOLD = 0.9;
	// "식감이 좋아요". 구체적인 식감 키워드와 함께 나오면 같은 말을 두 번 센 것이라 뺀다
	public static final String GENERAL_TEXTURE_KEYWORD_ID = "C25";
	public static final Set<String> SPECIFIC_TEXTURE_KEYWORD_IDS = Set.of("C18", "C19", "C20", "C21", "C22", "C23", "C26", "C28");

	public ReviewKeywordAnalysis {
		if (probabilities == null || keywordVersion == null || model == null) {
			throw new IllegalArgumentException("키워드별 확률, 키워드 목록 버전, 모델은 필수입니다.");
		}
		// containsKey(null)은 Map.of가 NPE를 던져 항목을 하나씩 본다
		if (probabilities.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null
			|| entry.getValue() < 0 || entry.getValue() > 1)) {
			throw new IllegalArgumentException("키워드별 확률은 0 이상 1 이하여야 합니다. probabilities=" + probabilities);
		}
		probabilities = Map.copyOf(probabilities);
	}

	/**
	 * 리뷰가 표현한 키워드 ID를 판정한다. 확률이 0.9 이상인 키워드를 남기고, 구체적인 식감 키워드가 하나라도 남으면 C25를 뺀다.
	 */
	public Set<String> expressedKeywordIds() {
		Set<String> expressed = probabilities.entrySet().stream()
			.filter(entry -> entry.getValue() >= EXPRESSED_THRESHOLD)
			.map(Map.Entry::getKey)
			.collect(Collectors.toCollection(HashSet::new));
		if (expressed.stream().anyMatch(SPECIFIC_TEXTURE_KEYWORD_IDS::contains)) {
			expressed.remove(GENERAL_TEXTURE_KEYWORD_ID);
		}
		return Set.copyOf(expressed);
	}

	/**
	 * 확률은 기준을 넘었지만 식감 규칙으로 표현하지 않은 것으로 본 키워드 ID다. 관리자 분석 테스트가 판정 이유를 보여 주려고 쓴다.
	 */
	public Set<String> excludedByTextureRuleKeywordIds() {
		Set<String> expressed = expressedKeywordIds();
		return probabilities.entrySet().stream()
			.filter(entry -> entry.getValue() >= EXPRESSED_THRESHOLD && !expressed.contains(entry.getKey()))
			.map(Map.Entry::getKey)
			.collect(Collectors.toUnmodifiableSet());
	}
}
