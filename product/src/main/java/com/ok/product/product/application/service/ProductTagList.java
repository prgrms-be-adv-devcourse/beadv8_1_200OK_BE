package com.ok.product.product.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.ok.product.product.domain.SearchTextNormalizer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상품 상황·용도 태그 목록({@code product-search/tags.json})을 읽는다. 평가셋으로 정밀도를 잰 목록(tags-v3)을 그대로 쓴다.
 * 목록이 코드와 맞지 않으면 앱이 뜨지 않는다. 선택지 값이 다르면 판정기 응답에서 확률을 꺼내지 못하고,
 * id가 겹치거나 비면 태그를 엉뚱한 상품에 붙이거나 저장하지 못하기 때문이다.
 */
@Component
public class ProductTagList {

	private static final String LOCATION = "product-search/tags.json";
	private static final List<String> CHOICE_VALUES = List.of("applies", "not_applies");
	// product_tag.tag_id, product_tag_judgment.top_tag_id 칼럼 길이다
	private static final int MAX_ID_LENGTH = 10;
	// product_tag_judgment.tag_version 칼럼 길이다
	private static final int MAX_VERSION_LENGTH = 20;

	private final String version;
	private final String instruction;
	private final List<Choice> choices;
	private final Map<String, Tag> tags;

	public ProductTagList() {
		this(read());
	}

	ProductTagList(JsonNode root) {
		this.version = root.path("version").asString();
		this.instruction = root.path("instruction").asString();
		this.choices = root.path("choices").values().stream()
			.map(choice -> new Choice(choice.path("value").asString(), choice.path("description").asString()))
			.toList();
		this.tags = new LinkedHashMap<>();
		for (JsonNode node : root.path("tags").values()) {
			Tag tag = new Tag(node.path("id").asString(), node.path("label").asString(),
				node.path("synonyms").values().stream().map(JsonNode::asString).toList(),
				node.path("description").asString());
			if (tags.putIfAbsent(tag.id(), tag) != null) {
				throw new IllegalStateException("태그 목록에 같은 id가 두 번 있습니다. id=" + tag.id());
			}
		}
		validate();
	}

	public String version() {
		return version;
	}

	// 모든 태그 질문 앞에 붙이는 공통 지시문이다
	public String instruction() {
		return instruction;
	}

	// 태그마다 묻는 두 선택지("해당", "해당 아님")다. 파일에 적힌 순서다
	public List<Choice> choices() {
		return choices;
	}

	// 파일에 적힌 순서다. 판정기는 이 순서로 묻는다
	public List<Tag> tags() {
		return List.copyOf(tags.values());
	}

	public Optional<Tag> find(String id) {
		return Optional.ofNullable(id).map(tags::get);
	}

	/**
	 * 붙인 태그의 이름과 동의어를 이어 검색 키워드로 정규화한다. 태그가 없으면 빈 문자열이다.
	 * 검색어 "아이 간식", "어린이 간식"이 상품명에 없는 상품도 찾게 하려는 값이라 목록 순서로 잇고, 목록에 없는 id는 건너뛴다.
	 */
	public String searchKeywords(Set<String> tagIds) {
		String source = tags.values().stream()
			.filter(tag -> tagIds.contains(tag.id()))
			.flatMap(tag -> Stream.concat(Stream.of(tag.label()), tag.synonyms().stream()))
			.collect(Collectors.joining(" "));
		return SearchTextNormalizer.text(source);
	}

	/**
	 * 목록의 태그 하나다.
	 *
	 * @param synonyms    검색 키워드에 라벨과 함께 넣는 다른 표현
	 * @param description 판정기 질문에 붙이는 판정 기준
	 */
	public record Tag(String id, String label, List<String> synonyms, String description) {
	}

	/**
	 * 태그 질문의 선택지 하나다.
	 *
	 * @param value 판정기가 답으로 고르는 값({@code applies}, {@code not_applies})
	 */
	public record Choice(String value, String description) {
	}

	private static JsonNode read() {
		try (InputStream input = new ClassPathResource(LOCATION).getInputStream()) {
			return JsonMapper.shared().readTree(input);
		} catch (IOException e) {
			throw new UncheckedIOException("태그 목록 파일을 읽지 못했습니다. location=" + LOCATION, e);
		}
	}

	private void validate() {
		if (version.isBlank() || version.length() > MAX_VERSION_LENGTH) {
			throw new IllegalStateException("태그 목록의 version이 없거나 " + MAX_VERSION_LENGTH + "자를 넘습니다. version=" + version);
		}
		List<String> choiceValues = choices.stream().map(Choice::value).toList();
		if (!choiceValues.equals(CHOICE_VALUES)) {
			throw new IllegalStateException(
				"태그 목록과 코드의 선택지가 다릅니다. list=" + choiceValues + ", code=" + CHOICE_VALUES);
		}
		if (tags.isEmpty()) {
			throw new IllegalStateException("태그 목록이 비었습니다. location=" + LOCATION);
		}
		for (Tag tag : tags.values()) {
			if (tag.id().isBlank() || tag.id().length() > MAX_ID_LENGTH || tag.label().isBlank()
				|| tag.description().isBlank()) {
				throw new IllegalStateException("태그의 id, label, description이 비었거나 id가 " + MAX_ID_LENGTH
					+ "자를 넘습니다. id=" + tag.id());
			}
		}
	}
}
