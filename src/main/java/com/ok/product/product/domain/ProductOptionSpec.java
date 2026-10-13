package com.ok.product.product.domain;

public record ProductOptionSpec(
	Long id,
	String name,
	Long listPrice,
	Long salePrice,
	int totalStock
) {

	// 중복 검사와 저장 전에 공백을 지운다
	public ProductOptionSpec {
		name = name == null ? null : name.strip();
	}

	public static ProductOptionSpec newOption(String name, Long listPrice, Long salePrice, int totalStock) {
		return new ProductOptionSpec(null, name, listPrice, salePrice, totalStock);
	}

	public boolean isNew() {
		return id == null;
	}
}
