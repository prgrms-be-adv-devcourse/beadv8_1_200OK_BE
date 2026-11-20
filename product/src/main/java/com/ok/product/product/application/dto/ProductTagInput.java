package com.ok.product.product.application.dto;

import java.util.List;

/**
 * 태그 판정기에 넘기는 상품 내용이다. 판정은 상품명과 옵션명만 본다(#746).
 *
 * @param optionNames 판매 중이거나 멈춘 옵션 중 삭제되지 않은 옵션의 이름. 상품 화면의 옵션 순서다
 */
public record ProductTagInput(
	Long productId,
	String productName,
	List<String> optionNames
) {

	public ProductTagInput {
		optionNames = List.copyOf(optionNames);
	}

	// 선택지 순서를 섞는 시드. 상품마다 고정해 같은 상품은 다시 판정해도 같은 순서로 묻는다
	public long choiceSeed() {
		return productId;
	}
}
