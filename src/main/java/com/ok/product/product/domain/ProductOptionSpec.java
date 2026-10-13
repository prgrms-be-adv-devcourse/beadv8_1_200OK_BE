package com.ok.product.product.domain;

/**
 * 상품 등록·수정 시 옵션 한 줄의 입력값.
 *
 * @param id         기존 옵션 수정이면 옵션 id, 새 옵션이면 null
 * @param listPrice  정가. null이면 판매가로 채운다
 * @param totalStock 새 옵션의 초기 총재고. 기존 옵션의 총재고 변경은 리포지토리 쿼리로 한다(updatable = false)
 */
public record ProductOptionSpec(
	Long id,
	String name,
	Long listPrice,
	Long salePrice,
	int totalStock
) {

	public static ProductOptionSpec newOption(String name, Long listPrice, Long salePrice, int totalStock) {
		return new ProductOptionSpec(null, name, listPrice, salePrice, totalStock);
	}

	public boolean isNew() {
		return id == null;
	}
}
