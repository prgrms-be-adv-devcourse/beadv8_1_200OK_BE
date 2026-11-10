package com.ok.product.review.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.review.domain.ReviewKeywordAspect;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ReviewKeywordListTest {

	@Test
	void product_리소스의_키워드_목록을_읽는다() {
		// when
		ReviewKeywordList sut = new ReviewKeywordList();

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.version()).isEqualTo("v0.3");
			softly.assertThat(sut.choices()).extracting(ReviewKeywordList.Choice::value)
				.containsExactly("mentioned", "not_mentioned");
			softly.assertThat(sut.find("C02")).hasValueSatisfying(keyword -> {
				softly.assertThat(keyword.phrase()).isEqualTo("달아요");
				softly.assertThat(keyword.aspect()).isEqualTo(ReviewKeywordAspect.TASTE);
			});
			softly.assertThat(sut.find("C13")).map(ReviewKeywordList.Keyword::aspect).hasValue(ReviewKeywordAspect.OTHER);
			softly.assertThat(sut.find("C99")).isEmpty();
		});
	}

	@Test
	void 하위_분류에_묻는_키워드는_전체_분류용과_그_분류용을_파일_순서로_고른다() {
		// given
		ReviewKeywordList list = new ReviewKeywordList();

		// when
		List<String> fruit = list.keywordsFor(ProductCategory.FRUIT).stream().map(ReviewKeywordList.Keyword::id).toList();
		List<String> grain = list.keywordsFor(ProductCategory.GRAIN).stream().map(ReviewKeywordList.Keyword::id).toList();

		// then
		assertSoftly(softly -> {
			softly.assertThat(fruit).startsWith("C01", "C02", "C03").contains("C22", "C25", "F02");
			softly.assertThat(grain).contains("C01", "C04", "C25").doesNotContain("C02", "C22");
		});
	}

	@ParameterizedTest
	@MethodSource("잘못된_목록")
	void 목록이_코드와_맞지_않으면_만들지_못한다(Consumer<ObjectNode> corrupt) {
		// given
		ObjectNode root = readRealList();
		corrupt.accept(root);

		// when & then
		assertThatThrownBy(() -> new ReviewKeywordList(root)).isInstanceOf(IllegalStateException.class);
	}

	static Stream<Named<Consumer<ObjectNode>>> 잘못된_목록() {
		return Stream.of(
			Named.of("코드에 없는 분류 이름", root -> keyword(root, 0).withArray("categories").add("SEAFOOD")),
			Named.of("코드에 없는 분석 항목", root -> keyword(root, 0).put("aspect", "smell")),
			Named.of("분석 항목을 대문자로 적음", root -> keyword(root, 0).put("aspect", "TASTE")),
			Named.of("한 분류에 키워드가 30개 넘음", root -> keywords(root).add(keyword(root, 0).deepCopy().put("id", "Z99"))),
			Named.of("같은 id가 두 번", root -> keyword(root, 1).put("id", "C01")),
			Named.of("식감 키워드 C22가 없음", root -> removeKeyword(root, "C22")),
			Named.of("식감이 좋아요 C25가 없음", root -> removeKeyword(root, "C25")),
			Named.of("버전이 비어 있음", root -> root.put("version", " ")),
			Named.of("선택지가 하나뿐", root -> root.withArray("choices").remove(1)),
			Named.of("선택지 순서가 다름", root -> {
				ArrayNode choices = root.withArray("choices");
				choices.add(choices.remove(0));
			})
		);
	}

	private static ObjectNode readRealList() {
		try (InputStream input = new ClassPathResource("review-analysis/keywords.json").getInputStream()) {
			return (ObjectNode)JsonMapper.shared().readTree(input);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ArrayNode keywords(ObjectNode root) {
		return root.withArray("keywords");
	}

	private static ObjectNode keyword(ObjectNode root, int index) {
		return (ObjectNode)keywords(root).get(index);
	}

	private static void removeKeyword(ObjectNode root, String id) {
		ArrayNode keywords = keywords(root);
		for (int i = 0; i < keywords.size(); i++) {
			if (id.equals(keywords.get(i).path("id").asString())) {
				keywords.remove(i);
				return;
			}
		}
		throw new IllegalArgumentException("목록에 없는 id입니다. id=" + id);
	}
}
