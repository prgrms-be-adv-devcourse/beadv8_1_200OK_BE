package com.ok.refund.fixture;

import com.ok.refund.domain.Refund;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

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

    /** 단위 테스트에서 저장 없이 ID를 붙인다 */
    public static Refund withId(Refund refund, Long id) {
        ReflectionTestUtils.setField(refund, "id", id);
        return refund;
    }
}
