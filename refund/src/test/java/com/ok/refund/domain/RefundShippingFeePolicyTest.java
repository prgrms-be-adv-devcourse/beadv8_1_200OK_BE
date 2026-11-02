package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ok.refund.fixture.RefundFixture.BUYER_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ID;
import static com.ok.refund.fixture.RefundFixture.SELLER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 기본 배송비 3,000원, 5만 원 이상 무료배송인 판매자 묶음 (요청서 #327 예시)
class RefundShippingFeePolicyTest {

    private static final long BASE_SHIPPING_FEE = 3_000L;
    private static final Long THRESHOLD = 50_000L;
    private static final Long A = 1L;
    private static final Long B = 2L;
    private static final Long C = 3L;

    @Test
    void 일부_취소로_무료배송_기준이_처음_깨지면_기본_배송비를_뺀다() {
        // given: A 30,000 + B 30,000 = 60,000원(무료배송), B 취소 → 남은 30,000원
        BundleShipping bundle = freeShipping(List.of(item(A, 30_000L), item(B, 30_000L)));

        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(requested(B, 30_000L), bundle, List.of(), 0L);

        // then
        assertThat(sut).isEqualTo(new RefundShippingFee(0L, 0L, 3_000L));
    }

    @Test
    void 일부_취소_뒤에도_기준_이상이면_빼지_않는다() {
        // given: A 50,000 + B 10,000 = 60,000원, B 취소 → 남은 50,000원(기준과 같으면 무료배송 유지)
        BundleShipping bundle = freeShipping(List.of(item(A, 50_000L), item(B, 10_000L)));

        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(requested(B, 10_000L), bundle, List.of(), 0L);

        // then
        assertThat(sut).isEqualTo(new RefundShippingFee(0L, 0L, 0L));
    }

    @Test
    void 앞_취소로_이미_기준_아래면_또_빼지_않는다() {
        // given: A 30,000 + B 30,000 + C 10,000 = 70,000원, A는 이미 취소(남은 40,000원, 앞 요청에서 뺌), 이번에 C
        BundleShipping bundle = freeShipping(List.of(item(A, 30_000L), item(B, 30_000L), item(C, 10_000L)));
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(A, 1L));

        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(requested(C, 10_000L), bundle, cancelled, 3_000L);

        // then
        assertThat(sut).isEqualTo(new RefundShippingFee(0L, 0L, 0L));
    }

    @Test
    void 무료배송_기준이_없으면_빼지_않는다() {
        // given: 처음부터 배송비를 낸 주문
        BundleShipping bundle = new BundleShipping(List.of(item(A, 30_000L), item(B, 30_000L)), 3_000L, 0L, BASE_SHIPPING_FEE, null);

        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(requested(B, 30_000L), bundle, List.of(), 0L);

        // then
        assertThat(sut).isEqualTo(new RefundShippingFee(0L, 0L, 0L));
    }

    @Test
    void 취소_금액이_기본_배송비와_같으면_0원_환불이_되도록_뺀다() {
        // given: A 48,000 + B 3,000 = 51,000원, B 취소 → 남은 48,000원
        BundleShipping bundle = freeShipping(List.of(item(A, 48_000L), item(B, 3_000L)));

        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(requested(B, 3_000L), bundle, List.of(), 0L);

        // then
        assertThat(sut).isEqualTo(new RefundShippingFee(0L, 0L, 3_000L));
    }

    @Test
    void 취소_금액이_기본_배송비보다_작으면_일부_취소할_수_없다() {
        // given: A 49,500 + B 1,000 = 50,500원, B 취소 → 남은 49,500원, 1,000 < 3,000
        BundleShipping bundle = freeShipping(List.of(item(A, 49_500L), item(B, 1_000L)));

        // when & then
        assertThatThrownBy(() -> RefundShippingFeePolicy.decide(requested(B, 1_000L), bundle, List.of(), 0L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED));
    }

    @Test
    void 묶음_전부_취소면_배송비_추가_운임과_앞에서_뺀_배송비를_돌려준다() {
        // given: A 30,000 + B 30,000, B는 앞서 취소하며 3,000원을 뺐고, 이번에 마지막 A
        BundleShipping bundle = new BundleShipping(List.of(item(A, 30_000L), item(B, 30_000L)), 0L, 2_500L, BASE_SHIPPING_FEE, THRESHOLD);
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(B, 1L));

        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(requested(A, 30_000L), bundle, cancelled, 3_000L);

        // then: 묶음 배송비 0 + 앞에서 뺀 3,000
        assertThat(sut).isEqualTo(new RefundShippingFee(3_000L, 2_500L, 0L));
    }

    @Test
    void 마지막_상품이면_배송비보다_싸도_막지_않는다() {
        // given: A 49,500 + B 1,000, A를 먼저 취소했고 이번에 마지막 B
        BundleShipping bundle = freeShipping(List.of(item(A, 49_500L), item(B, 1_000L)));
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(A, 1L));

        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(requested(B, 1_000L), bundle, cancelled, 3_000L);

        // then
        assertThat(sut).isEqualTo(new RefundShippingFee(3_000L, 0L, 0L));
    }

    // 무료배송이 적용되어 배송비 0원을 낸 묶음
    private static BundleShipping freeShipping(List<BundleItem> items) {
        return new BundleShipping(items, 0L, 0L, BASE_SHIPPING_FEE, THRESHOLD);
    }

    // 상품마다 1개씩 산 것으로 둔다
    private static BundleItem item(Long orderItemId, long unitPrice) {
        return new BundleItem(orderItemId, unitPrice, 1);
    }

    private static Refund requested(Long orderItemId, long unitPrice) {
        return Refund.request(ORDER_ID, orderItemId, 1, BUYER_ID, SELLER_ID, 55L, "상품", "옵션", unitPrice);
    }
}
