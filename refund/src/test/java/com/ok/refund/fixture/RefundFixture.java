package com.ok.refund.fixture;

import com.ok.refund.domain.Refund;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.refund.service.dto.SellerGroupItem;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 환불 테스트의 공통 값. 설계 문서의 예시(양말 3개, 개당 10,000원)를 기준으로 한다.
 */
public final class RefundFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);
    public static final Long ORDER_ID = 10L;
    public static final Long ORDER_ITEM_ID = 101L;
    public static final Long BUYER_ID = 1L;
    public static final Long SELLER_ID = 5L;
    public static final Long PRODUCT_OPTION_ID = 55L;
    public static final long UNIT_PRICE = 10_000L;
    public static final Long HAT_ORDER_ITEM_ID = 102L;
    public static final List<SellerGroupItem> BUNDLE = List.of(
            new SellerGroupItem(ORDER_ITEM_ID, 3), new SellerGroupItem(HAT_ORDER_ITEM_ID, 1));

    private RefundFixture() {
    }

    /** 양말(주문 상품 101) 요청됨 */
    public static Refund requested(int quantity) {
        return requested(ORDER_ITEM_ID, quantity);
    }

    public static Refund requested(Long orderItemId, int quantity) {
        return Refund.request(ORDER_ID, orderItemId, quantity, BUYER_ID, SELLER_ID, PRODUCT_OPTION_ID,
                "양말", "흰색", UNIT_PRICE);
    }

    /** 같은 묶음의 모자(주문 상품 102, 5,000원) 요청됨 */
    public static Refund requestedHat() {
        return Refund.request(ORDER_ID, HAT_ORDER_ITEM_ID, 1, BUYER_ID, SELLER_ID, 56L, "모자", "검정", 5_000L);
    }

    /**
     * 주문 모듈이 돌려줄 양말 정보. 같은 판매자 묶음에 양말(101) 3개 + 모자(102) 1개, 배송비 3,000원, 추가 운임 3,000원.
     */
    public static RefundableOrderItem socks() {
        return socks(RefundableOrderItem.CANCELLABLE_STATUS);
    }

    public static RefundableOrderItem socks(String status) {
        return new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, PRODUCT_OPTION_ID, "양말", "흰색",
                status, UNIT_PRICE, 3, 3_000L, 3_000L, BUNDLE);
    }

    /** 같은 묶음의 모자 (주문 상품 102, 옵션 56, 5,000원 1개) */
    public static RefundableOrderItem hat() {
        return new RefundableOrderItem(ORDER_ID, HAT_ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 56L, "모자", "검정",
                RefundableOrderItem.CANCELLABLE_STATUS, 5_000L, 1, 3_000L, 3_000L, BUNDLE);
    }

    /** 단위 테스트에서 저장 없이 ID를 붙인다 */
    public static Refund withId(Refund refund, Long id) {
        ReflectionTestUtils.setField(refund, "id", id);
        return refund;
    }
}
