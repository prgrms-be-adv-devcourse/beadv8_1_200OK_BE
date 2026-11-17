package com.ok.product.product.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.ok.product.product.domain.SearchTextNormalizer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 품목 사전({@code product-search/items.json})으로 상품명의 품목을 찾고, 그 상품을 찾을 한 글자 품목을 고른다.
 * 고른 값은 {@code product_search.search_item}에 저장되고, 1글자 검색어("귤")는 이 값과의 정확 일치로 찾는다.
 * MySQL ngram FULLTEXT는 1글자 단어를 인덱싱하지 않아 FULLTEXT로는 1글자 검색어를 찾지 못하기 때문이다.
 * 품목 사전 파일에는 품목만 둔다. 오타 교정 제안(F7)도 같은 파일을 쓴다.
 * 파일이 없거나, 품목이 하나도 없거나, 품목이 20자를 넘거나, 한 글자 품목 표가 품목 사전과 맞지 않으면 앱이 뜨지 않는다.
 */
@Component
public class ProductItemDictionary {

	private static final String LOCATION = "product-search/items.json";
	// product_search.search_item 컬럼 길이다. 넘는 품목이 저장되면 INSERT가 실패해 상품 등록이 통째로 롤백되므로 기동 때 막는다
	private static final int MAX_ITEM_LENGTH = 20;
	// 한 글자 품목 표를 품목 사전과 따로 두는 이유는 F7이 품목 사전을 품목 목록으로만 읽기 때문이다.
	// 글자를 포함하는지로 자동으로 묶지 않고 사람이 고른 표를 쓰는 이유는 배추가 "배"로, 감자가 "감"으로 찾히면 안 되기 때문이다
	private static final String SINGLE_CHAR_LOCATION = "product-search/single-char-items.json";

	private final List<String> items;
	private final Map<String, String> singleCharItemByItem;

	public ProductItemDictionary() {
		this(read(), readSingleCharItems());
	}

	/**
	 * 품목 사전과 한 글자 품목 표로 사전을 만든다. 테스트에서 파일 대신 작은 사전을 넣을 때 쓴다.
	 *
	 * @param singleCharItems 한 글자 품목을 키로, 그 글자로 찾을 품목을 값으로 둔 표
	 */
	ProductItemDictionary(List<String> items, Map<String, List<String>> singleCharItems) {
		this.items = items.stream()
			.map(SearchTextNormalizer::compact)
			.filter(item -> !item.isEmpty())
			.distinct()
			.toList();
		if (this.items.isEmpty()) {
			throw new IllegalStateException("품목 사전에 품목이 없습니다. location=" + LOCATION);
		}
		this.items.stream()
			.filter(item -> item.length() > MAX_ITEM_LENGTH)
			.findFirst()
			.ifPresent(item -> {
				throw new IllegalStateException("품목이 search_item 컬럼 길이(" + MAX_ITEM_LENGTH + "자)를 넘습니다. item=" + item
					+ ", location=" + LOCATION);
			});
		this.singleCharItemByItem = toSingleCharItemByItem(singleCharItems);
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

	/**
	 * 상품을 1글자 검색어로 찾을 때 쓸 한 글자 품목을 고른다.
	 * 가장 긴 품목({@link #findLongestItem(String)})을 한 글자 품목 표에서 찾는다. "제주감귤"은 "귤"이 되고,
	 * 가장 긴 품목이 "배추"인 "해남배추"는 표에 없어 "배"가 되지 않는다.
	 *
	 * @return 한 글자 품목. 상품명에 품목이 없거나 가장 긴 품목이 표에 없으면 빈 값
	 */
	public Optional<String> findSingleCharItem(String productName) {
		return findLongestItem(productName).map(singleCharItemByItem::get);
	}

	private Map<String, String> toSingleCharItemByItem(Map<String, List<String>> singleCharItems) {
		Map<String, String> singleCharItemByItem = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : singleCharItems.entrySet()) {
			String singleCharItem = SearchTextNormalizer.compact(entry.getKey());
			if (singleCharItem.codePointCount(0, singleCharItem.length()) != 1) {
				throw new IllegalStateException("한 글자 품목 표의 키가 한 글자가 아닙니다. key=" + entry.getKey()
					+ ", location=" + SINGLE_CHAR_LOCATION);
			}
			requireInItems(singleCharItem);
			for (String rawItem : entry.getValue()) {
				String item = SearchTextNormalizer.compact(rawItem);
				requireInItems(item);
				if (singleCharItemByItem.putIfAbsent(item, singleCharItem) != null) {
					throw new IllegalStateException("한 글자 품목 표에 같은 품목이 두 번 있습니다. item=" + item
						+ ", location=" + SINGLE_CHAR_LOCATION);
				}
			}
		}
		return Map.copyOf(singleCharItemByItem);
	}

	private void requireInItems(String item) {
		if (!items.contains(item)) {
			throw new IllegalStateException("한 글자 품목 표에 품목 사전에 없는 품목이 있습니다. item=" + item
				+ ", location=" + SINGLE_CHAR_LOCATION);
		}
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

	private static Map<String, List<String>> readSingleCharItems() {
		try (InputStream input = new ClassPathResource(SINGLE_CHAR_LOCATION).getInputStream()) {
			return JsonMapper.shared().readValue(input, new TypeReference<>() {
			});
		} catch (IOException e) {
			throw new UncheckedIOException("한 글자 품목 표 파일을 읽지 못했습니다. location=" + SINGLE_CHAR_LOCATION, e);
		}
	}
}
