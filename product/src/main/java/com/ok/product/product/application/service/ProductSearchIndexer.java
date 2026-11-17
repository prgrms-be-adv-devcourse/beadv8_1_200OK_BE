package com.ok.product.product.application.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.ok.product.product.application.port.out.ProductSearchRepository;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductSearch;
import com.ok.product.product.domain.SearchTextNormalizer;

import lombok.RequiredArgsConstructor;

/**
 * 상품 정보로 검색 행({@link ProductSearch})을 계산해 저장한다.
 * 트랜잭션을 열지 않고 부르는 쪽(상품 등록·수정) 트랜잭션에 참여한다. 검색 행 저장이 실패하면 상품 저장도 롤백되어
 * "등록했는데 검색되지 않는" 상품이 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ProductSearchIndexer {

	private final ProductSearchRepository productSearchRepository;
	private final ProductItemDictionary productItemDictionary;

	/**
	 * 상품의 검색 행을 다시 계산한다. 행이 없으면 만들고, 있으면 값을 바꾼다.
	 * 상품 ID가 있어야 하므로 상품을 저장한 뒤에 부른다.
	 */
	public void index(Product product) {
		apply(product, productSearchRepository.findById(product.getId()));
	}

	/**
	 * 여러 상품의 검색 행을 다시 계산한다. 기존 행을 한 번에 읽어 상품마다 조회하지 않는다. 동작은 {@link #index(Product)}와 같다.
	 */
	public void indexAll(List<Product> products) {
		Map<Long, ProductSearch> existing = productSearchRepository
			.findAllById(products.stream().map(Product::getId).toList()).stream()
			.collect(Collectors.toMap(ProductSearch::getProductId, Function.identity()));
		products.forEach(product -> apply(product, Optional.ofNullable(existing.get(product.getId()))));
	}

	// index와 indexAll이 갈라지지 않도록 계산과 저장을 한곳에 둔다
	private void apply(Product product, Optional<ProductSearch> existing) {
		String name = product.getName();
		String nameCompact = SearchTextNormalizer.compact(name);
		String nameJamo = SearchTextNormalizer.jamo(name);
		String searchBody = SearchTextNormalizer.text(searchBodySource(product));
		String searchItem = productItemDictionary.findSingleCharItem(name).orElse(null);

		existing.ifPresentOrElse(
			productSearch -> productSearch.refresh(nameCompact, nameJamo, searchBody, searchItem,
				SearchTextNormalizer.VERSION),
			() -> productSearchRepository.save(ProductSearch.create(product.getId(), nameCompact, nameJamo,
				searchBody, searchItem, SearchTextNormalizer.VERSION)));
	}

	// 설명(descriptionHtml)은 넣지 않는다. 실험에서 설명을 넣으면 무관한 상품이 결과에 더 많이 섞였다
	private String searchBodySource(Product product) {
		return Stream.concat(
				product.activeOptions().stream().map(ProductOption::getName),
				Stream.of(product.getCategory().getDescription()))
			.collect(Collectors.joining(" "));
	}
}
