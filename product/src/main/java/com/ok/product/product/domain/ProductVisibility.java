package com.ok.product.product.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProductVisibility {
	VISIBLE("노출"),
	HIDDEN("숨김");

	private final String description;
}
