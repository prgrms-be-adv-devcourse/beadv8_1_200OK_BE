package com.ok.product.product.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

	// 상품
	PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found", "상품을 찾을 수 없습니다."),
	PRODUCT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Product access denied", "본인 상품만 조회하거나 수정·삭제할 수 있습니다."),
	INVALID_IMAGE_URL(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid image url", "업로드한 상품 이미지 주소가 아닙니다."),
	DESCRIPTION_TOO_LONG(HttpStatus.UNPROCESSABLE_ENTITY, "Description too long", "상세설명이 너무 깁니다."),
	PRODUCT_CONCURRENT_MODIFICATION(HttpStatus.CONFLICT,"Product concurrent modification", "다른 사용자가 이미 정보를 수정했습니다. 새로고침 후 다시 시도해주세요."),

	// 옵션
	OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Option not found", "상품의 옵션을 찾을 수 없습니다."),
	DUPLICATE_OPTION_NAME(HttpStatus.CONFLICT, "Duplicate option name", "같은 상품 안에 같은 이름의 옵션이 있습니다."),
	PRICE_EXCEEDS_LIST_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Price exceeds list price", "판매가는 정가보다 클 수 없습니다."),
	STOCK_BELOW_ALLOCATED(HttpStatus.UNPROCESSABLE_ENTITY, "Stock below allocated", "재고는 주문에 배정된 수량보다 적게 줄일 수 없습니다."),
	OPTION_NOT_PURCHASABLE(HttpStatus.CONFLICT, "Option not purchasable", "판매 중인 옵션이 아닙니다."),
	INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock", "재고가 부족합니다."),
	ALLOCATION_ALREADY_RELEASED(HttpStatus.CONFLICT, "Allocation already released", "이미 재고를 해제한 주문입니다. 새 주문으로 요청해주세요."),
	ALLOCATION_IN_PROGRESS(HttpStatus.CONFLICT, "Allocation in progress", "같은 주문의 재고 배정이 처리 중입니다. 잠시 후 다시 요청해주세요.");

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
