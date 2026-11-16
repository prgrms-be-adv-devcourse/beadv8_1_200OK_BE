package com.ok.product.product.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.ok.product.product.domain.SearchTextNormalizer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 품목 사전({@code product-search/items.json})으로 상품명의 대표 품목을 고른다.
 * 고른 값은 {@code product_search.search_item}에 저장되고, 1글자 검색어("배")는 이 값과의 정확 일치로 찾는다.
 * MySQL ngram FULLTEXT는 1글자 단어를 인덱싱하지 않아 FULLTEXT로는 1글자 검색어를 찾지 못하기 때문이다.
 * 파일에는 품목만 둔다. 오타 교정 제안(F7)도 같은 파일을 쓴다.
 * 파일이 없거나 품목이 하나도 없으면 앱이 뜨지 않는다.
 */
@Component
public class ProductItemDictionary {

	private static final String LOCATION = "product-search/items.json";

	private final List<String> items;

	public ProductItemDictionary() {
		this(read());
	}

	ProductItemDictionary(List<String> items) {
		this.items = items.stream()
			.map(SearchTextNormalizer::compact)
			.filter(item -> !item.isEmpty())
			.distinct()
			.toList();
		if (this.items.isEmpty()) {
			throw new IllegalStateException("품목 사전에 품목이 없습니다. location=" + LOCATION);
		}
	}

	/**
	 * 상품명에 들어 있는 품목 중 가장 긴 것을 고른다. 길이가 같으면 상품명에서 더 앞에 나온 것이다.
	 * 가장 긴 것을 고르는 이유는 "해남배추"를 "배"가 아니라 "배추"로 분류하기 위해서다.
	 * 상품명과 품목은 {@link SearchTextNormalizer#compact(String)}로 바꾼 뒤 비교하므로 띄어쓰기를 무시한다.
	 *
	 * @return {@code compact}로 바꾼 품목. 들어 있는 품목이 없으면 빈 값
	 */
	public Optional<String> findLongestItem(String productName) {
		String name = SearchTextNormalizer.compact(productName);
		return items.stream()
			.filter(name::contains)
			.min(Comparator.comparingInt(String::length).reversed()
				.thenComparingInt(name::indexOf));
	}

	private static List<String> read() {
		try (InputStream input = new ClassPathResource(LOCATION).getInputStream()) {
			return JsonMapper.shared().readTree(input).values().stream()
				.map(JsonNode::asString)
				.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("품목 사전 파일을 읽지 못했습니다. location=" + LOCATION, e);
		}
	}
}
