package com.ok.product.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ok.product.product.application.port.out.ProductSearchRepository;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearch;
import com.ok.product.product.domain.SearchTextNormalizer;

@ExtendWith(MockitoExtension.class)
class ProductSearchIndexerTest {

	private static final Long PRODUCT_ID = 10L;

	@Mock
	ProductSearchRepository productSearchRepository;

	ProductSearchIndexer sut;

	@BeforeEach
	void setUp() {
		sut = new ProductSearchIndexer(productSearchRepository, new ProductItemDictionary(List.of("귤", "감귤", "한라봉")));
	}

	@Test
	void 검색_행이_없으면_상품명_옵션명_카테고리로_계산해_새로_저장한다() {
		// given
		Product product = product("[서귀포] 하우스감귤 5kg", "<p>제주 직송</p>", ProductCategory.FRUIT,
			List.of(ProductOptionSpec.newOption("가정용 3kg", null, 20_000L, 10),
				ProductOptionSpec.newOption("선물용", null, 30_000L, 10)));
		given(productSearchRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

		// when
		sut.index(product);

		// then
		ProductSearch saved = savedProductSearch();
		assertThat(saved).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
			softly.assertThat(saved.getNameCompact()).isEqualTo("서귀포하우스감귤5kg");
			softly.assertThat(saved.getNameJamo()).isEqualTo("ㅅㅓㄱㅟㅍㅗㅎㅏㅇㅜㅅㅡㄱㅏㅁㄱㅠㄹ5kg");
			// 카테고리는 한글명(과일)으로 넣고, 설명의 "제주 직송"은 넣지 않는다
			softly.assertThat(saved.getSearchBody()).isEqualTo("가정용 3kg 선물용 과일");
			softly.assertThat(saved.getKeywords()).isEmpty();
			softly.assertThat(saved.getSearchItem()).isEqualTo("감귤");
			softly.assertThat(saved.getNormalizerVersion()).isEqualTo(SearchTextNormalizer.VERSION);
		});
	}

	@Test
	void 삭제된_옵션명은_search_body에_넣지_않는다() {
		// given
		Product product = product("하우스감귤", "<p>설명</p>", ProductCategory.FRUIT,
			List.of(ProductOptionSpec.newOption("가정용", null, 20_000L, 10),
				ProductOptionSpec.newOption("선물용", null, 30_000L, 10)));
		ReflectionTestUtils.setField(product.getOptions().get(0), "id", 1L);
		ReflectionTestUtils.setField(product.getOptions().get(1), "id", 2L);
		product.mergeOptions(List.of(new ProductOptionSpec(1L, "가정용", null, 20_000L, 10)), LocalDateTime.now());
		given(productSearchRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

		// when
		sut.index(product);

		// then
		assertThat(savedProductSearch().getSearchBody()).isEqualTo("가정용 과일");
	}

	@Test
	void 상품명에_사전의_품목이_없으면_search_item은_null이다() {
		// given
		Product product = product("유기농 쌀 10kg", "<p>설명</p>", ProductCategory.GRAIN,
			List.of(ProductOptionSpec.newOption("10kg", null, 40_000L, 10)));
		given(productSearchRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

		// when
		sut.index(product);

		// then
		assertThat(savedProductSearch().getSearchItem()).isNull();
	}

	@Test
	void 검색_행이_있으면_다시_계산하고_keywords는_그대로_둔다() {
		// given
		ProductSearch existing = ProductSearch.create(PRODUCT_ID, "옛이름", "ㅇㅖㅅ", "옛 옵션", "귤", 0);
		ReflectionTestUtils.setField(existing, "keywords", "새콤 달콤");
		given(productSearchRepository.findById(PRODUCT_ID)).willReturn(Optional.of(existing));
		Product product = product("제주 한라봉 3kg", "<p>설명</p>", ProductCategory.FRUIT,
			List.of(ProductOptionSpec.newOption("특대", null, 30_000L, 10)));

		// when
		sut.index(product);

		// then
		assertThat(existing).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(existing.getNameCompact()).isEqualTo("제주한라봉3kg");
			softly.assertThat(existing.getNameJamo()).isEqualTo("ㅈㅔㅈㅜㅎㅏㄴㄹㅏㅂㅗㅇ3kg");
			softly.assertThat(existing.getSearchBody()).isEqualTo("특대 과일");
			softly.assertThat(existing.getSearchItem()).isEqualTo("한라봉");
			softly.assertThat(existing.getNormalizerVersion()).isEqualTo(SearchTextNormalizer.VERSION);
			softly.assertThat(existing.getKeywords()).isEqualTo("새콤 달콤");
		});
	}

	private Product product(String name, String descriptionHtml, ProductCategory category,
		List<ProductOptionSpec> options) {
		Product product = Product.create(1L, name, descriptionHtml, category, ProductRegion.JEJU, options,
			List.of("product/a.jpg"));
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		return product;
	}

	private ProductSearch savedProductSearch() {
		ArgumentCaptor<ProductSearch> captor = ArgumentCaptor.forClass(ProductSearch.class);
		then(productSearchRepository).should().save(captor.capture());
		return captor.getValue();
	}
}
