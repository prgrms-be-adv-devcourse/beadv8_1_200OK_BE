package com.ok.product.product.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProductStatus {
	READY("판매 준비"),
	ON_SALE("판매 중"),
	PAUSED("일시 중지"),
	ENDED("판매 종료");

	private final String description;
}
