package com.ok.product.review.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.review.domain.ReviewKeywordAspect;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구매자 리뷰 키워드 목록({@code review-analysis/keywords.json})을 읽는다. 모델 비교 실험(#385)이 정밀도를 잰 목록을 그대로 쓴다.
 * 목록이 코드와 맞지 않으면 앱이 뜨지 않는다. 분류 이름이나 분석 항목이 틀리면 키워드를 엉뚱한 상품에 묻거나 응답을 만들지 못하고,
 * 식감 규칙의 키워드가 목록에서 빠지면 판정이 조용히 달라지기 때문이다.
 */
// TODO(#422): 목록 버전이 바뀌어도 이미 분석한 리뷰를 일괄 재분석하지 않는다. 결과의 keyword_version으로 대상을 고를 수 있다
@Component
public class ReviewKeywordList {

	// Jev 요청 한 번에 담는 질문 수를 묶어 둔다. 실험이 분류당 12~30개로 정밀도를 쟀다
	static final int MAX_KEYWORDS_PER_CATEGORY = 30;

	private static final String LOCATION = "review-analysis/keywords.json";
	private static final String ALL_CATEGORIES = "ALL";
	private static final List<String> CHOICE_VALUES = List.of("mentioned", "not_mentioned");

	private final String version;
	private final String instruction;
	private final List<Choice> choices;
	private final Map<String, Keyword> keywords;

	public ReviewKeywordList() {
		this(read());
	}

	ReviewKeywordList(JsonNode root) {
		this.version = root.path("version").asString();
		this.instruction = root.path("instruction").asString();
		this.choices = root.path("choices").values().stream()
			.map(choice -> new Choice(choice.path("value").asString(), choice.path("description").asString()))
			.toList();
		this.keywords = new LinkedHashMap<>();
		for (JsonNode node : root.path("keywords").values()) {
			Keyword keyword = toKeyword(node);
			if (keywords.putIfAbsent(keyword.id(), keyword) != null) {
				throw new IllegalStateException("키워드 목록에 같은 id가 두 번 있습니다. id=" + keyword.id());
			}
		}
		validate();
	}

	public String version() {
		return version;
	}

	// 모든 키워드 질문 앞에 붙이는 공통 지시문이다
	public String instruction() {
		return instruction;
	}

	// 키워드마다 묻는 두 선택지("말함", "안 말함")다. 파일에 적힌 순서다
	public List<Choice> choices() {
		return choices;
	}

	// 하위 분류에 묻는 키워드를 파일에 적힌 순서로 돌려준다
	public List<Keyword> keywordsFor(ProductCategory category) {
		return keywords.values().stream()
			.filter(keyword -> keyword.appliesTo(category))
			.toList();
	}

	public Optional<Keyword> find(String id) {
		return Optional.ofNullable(keywords.get(id));
	}

	/**
	 * 목록의 키워드 하나다.
	 *
	 * @param guide      분석기 질문에 붙이는 판정 기준
	 * @param categories 이 키워드를 묻는 하위 분류. 파일의 {@code ALL}은 모든 분류로 펼친다
	 */
	public record Keyword(String id, String phrase, ReviewKeywordAspect aspect, String guide, Set<ProductCategory> categories) {

		public boolean appliesTo(ProductCategory category) {
			return categories.contains(category);
		}
	}

	/**
	 * 키워드 질문의 선택지 하나다.
	 *
	 * @param value 분석기가 답으로 고르는 값({@code mentioned}, {@code not_mentioned})
	 */
	public record Choice(String value, String description) {
	}

	private static JsonNode read() {
		try (InputStream input = new ClassPathResource(LOCATION).getInputStream()) {
			return JsonMapper.shared().readTree(input);
		} catch (IOException e) {
			throw new UncheckedIOException("키워드 목록 파일을 읽지 못했습니다. location=" + LOCATION, e);
		}
	}

	private static Keyword toKeyword(JsonNode node) {
		String id = node.path("id").asString();
		return new Keyword(id, node.path("phrase").asString(), toAspect(id, node.path("aspect").asString()),
			node.path("guide").asString(), toCategories(id, node.path("categories")));
	}

	private static ReviewKeywordAspect toAspect(String id, String aspect) {
		return Arrays.stream(ReviewKeywordAspect.values())
			.filter(value -> value.name().toLowerCase(Locale.ROOT).equals(aspect))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
				"키워드 목록의 aspect가 코드의 분석 항목에 없습니다. id=" + id + ", aspect=" + aspect));
	}

	private static Set<ProductCategory> toCategories(String id, JsonNode categories) {
		Set<ProductCategory> result = EnumSet.noneOf(ProductCategory.class);
		for (JsonNode node : categories.values()) {
			String name = node.asString();
			if (ALL_CATEGORIES.equals(name)) {
				result.addAll(EnumSet.allOf(ProductCategory.class));
			} else {
				result.add(Arrays.stream(ProductCategory.values())
					.filter(category -> category.name().equals(name))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException(
						"키워드 목록의 분류 이름이 코드의 하위 분류에 없습니다. id=" + id + ", category=" + name)));
			}
		}
		return Set.copyOf(result);
	}

	private void validate() {
		if (version.isBlank()) {
			throw new IllegalStateException("키워드 목록에 version이 없습니다. location=" + LOCATION);
		}
		List<String> choiceValues = choices.stream().map(Choice::value).toList();
		if (!choiceValues.equals(CHOICE_VALUES)) {
			throw new IllegalStateException(
				"키워드 목록과 코드의 선택지가 다릅니다. list=" + choiceValues + ", code=" + CHOICE_VALUES);
		}
		for (ProductCategory category : ProductCategory.values()) {
			int count = keywordsFor(category).size();
			if (count > MAX_KEYWORDS_PER_CATEGORY) {
				throw new IllegalStateException("하위 분류에 묻는 키워드가 " + MAX_KEYWORDS_PER_CATEGORY
					+ "개를 넘습니다. category=" + category + ", count=" + count);
			}
		}
		Set<String> textureIds = new HashSet<>(ReviewKeywordAnalysis.SPECIFIC_TEXTURE_KEYWORD_IDS);
		textureIds.add(ReviewKeywordAnalysis.GENERAL_TEXTURE_KEYWORD_ID);
		List<String> missing = textureIds.stream()
			.filter(id -> !keywords.containsKey(id))
			.sorted()
			.toList();
		if (!missing.isEmpty()) {
			throw new IllegalStateException("식감 규칙의 키워드가 목록에 없습니다. missing=" + missing);
		}
	}
}
