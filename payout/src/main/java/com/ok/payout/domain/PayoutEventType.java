package com.ok.payout.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Set;

@Getter
@RequiredArgsConstructor
public enum PayoutEventType {

    // 구매 확정
    SALE_AMOUNT("상품판매 대금"),
    SALE_FEE("상품판매 수수료"),
    SALE_SHIPPING_FEE("상품판매 배송비");

    //만일 환불시 기억위해서 남김
    // 구매 확정 정산 완료 후 환불
    //SALE_AMOUNT_CANCEL("상품판매 대금 취소"),
    // SALE_FEE_CANCEL("상품판매 수수료 취소"),
    //SALE_SHIPPING_FEE_REFUND("상품판매 배송비 환불"),

    // 구매 확정 후 정산 전 환불
    //SALE_AMOUNT_CANCEL_BEFORE_PAYOUT("상품판매 대금 정산 전 취소"),
    //SALE_FEE_CANCEL_BEFORE_PAYOUT("상품판매 수수료 정산 전 취소"),
    //SALE_SHIPPING_FEE_CANCEL_BEFORE_PAYOUT("상품판매 배송비 정산 전 취소");

    private final String description;

    // 구매확정 이벤트로 만들어지는 타입 묶음
    public static final Set<PayoutEventType> PURCHASE_CONFIRMED_TYPES =
            EnumSet.of(SALE_AMOUNT, SALE_FEE, SALE_SHIPPING_FEE);
}