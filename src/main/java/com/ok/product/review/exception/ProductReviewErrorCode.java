package com.ok.product.review.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductReviewErrorCode implements ErrorCode {

	REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "Review not found", "리뷰를 찾을 수 없습니다."),
	REVIEW_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Review access denied", "리뷰를 수정하거나 삭제할 권한이 없습니다."),
	PURCHASE_NOT_REVIEWABLE(HttpStatus.FORBIDDEN, "Purchase not reviewable", "본인이 구매확정한 주문 상품에만 리뷰를 쓸 수 있습니다."),
	ORDER_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Order API failed", "구매 내역을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
	DUPLICATE_REVIEW(HttpStatus.CONFLICT, "Duplicate review", "이 주문에서 이미 리뷰를 작성한 상품입니다."),
	INVALID_REVIEW_MEDIA_URL(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid review media url", "업로드한 리뷰 첨부 주소가 아닙니다."),
	REVIEW_MEDIA_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Review media limit exceeded", "리뷰 영상은 1개까지 첨부할 수 있습니다.");

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
