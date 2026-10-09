package com.ok.payout.domain;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PayoutErrorCode implements ErrorCode {

    PAYOUT_CANDIDATE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Payout candidate already exists", "이미 생성된 정산후보입니다."),
    MISSING_ORDER_ITEM_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing order item id", "주문항목 ID는 필수입니다."),
    MISSING_SELLER_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing seller id", "판매자 ID는 필수입니다."),
    INVALID_TOTAL_ITEM_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid total item price", "금액은 0 이상이어야 합니다."),
    INVALID_SHIPPING_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid shipping price", "배송비는 존재해야되며 음수가 될수 없습니다.");


    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }
}
