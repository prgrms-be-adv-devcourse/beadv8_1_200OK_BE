package com.ok.product.product.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

import static com.ok.product.product.domain.ProductCategoryGroup.AGRICULTURE;

// 하위 카테고리
@Getter
@RequiredArgsConstructor
public enum ProductCategory {
	GRAIN(AGRICULTURE, "쌀·잡곡"),
	VEGETABLE(AGRICULTURE, "채소"),
	FRUIT(AGRICULTURE, "과일"),
	ROOT_CROP(AGRICULTURE, "감자·고구마"),
	MUSHROOM(AGRICULTURE, "버섯"),
	NUT(AGRICULTURE, "견과·땅콩"),
	WILD_GREENS(AGRICULTURE, "나물·산채"),
	PROCESSED_AGRI(AGRICULTURE, "농산 가공품"); // 즙·분말·건조 농산물

	private final ProductCategoryGroup group;
	private final String description;

	public static List<ProductCategory> findAllByGroup(ProductCategoryGroup group) {
		return Arrays.stream(values())
			.filter(category -> category.group == group)
			.toList();
	}
}
