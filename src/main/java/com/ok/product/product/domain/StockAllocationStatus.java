package com.ok.product.product.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StockAllocationStatus {
	ALLOCATED("배정"),
	RELEASED("해제");

	private final String description;
}
