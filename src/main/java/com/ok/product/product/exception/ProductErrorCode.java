package com.ok.product.product.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

	// 상품
	PRODUCT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Product access denied", "상품을 등록하거나 수정할 권한이 없습니다."),
	INVALID_IMAGE_URL(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid image url", "업로드한 상품 이미지 주소가 아닙니다."),
	DESCRIPTION_TOO_LONG(HttpStatus.UNPROCESSABLE_ENTITY, "Description too long", "상세설명이 너무 깁니다."),

	// 옵션
	OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Option not found", "상품의 옵션을 찾을 수 없습니다."),
	DUPLICATE_OPTION_NAME(HttpStatus.CONFLICT, "Duplicate option name", "같은 상품 안에 같은 이름의 옵션이 있습니다."),
	PRICE_EXCEEDS_LIST_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Price exceeds list price", "판매가는 정가보다 클 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String title;
	private final String message;

	@Override
	public HttpStatus status() {
		return httpStatus;
	}

	@Override
	public String slug() {
		return "product/" + ErrorCode.super.slug();
	}
}
