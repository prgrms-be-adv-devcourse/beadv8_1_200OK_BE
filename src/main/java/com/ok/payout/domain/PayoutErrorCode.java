package com.ok.payout.domain;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PayoutErrorCode implements ErrorCode {

    MISSING_ORDER_ITEM_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing order item id", "주문항목 ID는 필수입니다."),
    MISSING_SELLER_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing seller id", "판매자 ID는 필수입니다."),
    INVALID_TOTAL_ITEM_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid total item price", "상품 총액은 필수이며 0 이상이어야 합니다."),
    INVALID_SHIPPING_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid shipping price", "배송비는 필수이며 0 이상이어야 합니다."),
    MISSING_PAYOUT_REQUESTED_AT(HttpStatus.INTERNAL_SERVER_ERROR, "Missing payout requested at", "정산 Job 실행에는 requestedAt 파라미터가 필요합니다."),
    UNSETTLED_CANDIDATES_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "Unsettled candidates not found", "정산 대상 판매자의 미정산 후보가 없습니다. 조회 조건 불일치를 확인하세요.");


    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }
}
