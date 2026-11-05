package com.ok.refund.domain;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 예시 값: 기본 배송비 3,000원, 50,000원 이상 무료배송. 상품 ID는 101(A), 102(B), 103(C)
class RefundShippingFeePolicyTest {

    private static final long BASE_FEE = 3_000L;
    private static final Long THRESHOLD = 50_000L;
    private static final Long A = 101L;
    private static final Long B = 102L;
    private static final Long C = 103L;

    @ParameterizedTest(name = "{0}")
    @MethodSource("배송비_판단_경우")
    void 배송비_판단_경우별_결과(
            Refund target,
            BundleShipping bundle,
            List<CancelledQuantity> cancelled,
            long previouslyDeducted,
            RefundShippingFee expected
    ) {
        // when
        RefundShippingFee sut = RefundShippingFeePolicy.decide(target, bundle, cancelled, previouslyDeducted);

        // then
        assertThat(sut).isEqualTo(expected);
    }

    static Stream<Arguments> 배송비_판단_경우() {
        return Stream.of(
                Arguments.of(Named.of("한 상품 묶음을 전부 취소하면 낸 배송비와 도서산간 운임을 돌려준다", refund(A, 20_000L, 1)),
                        bundle(3_000L, 2_000L, THRESHOLD, item(A, 20_000L, 1)),
                        List.of(), 0L, new RefundShippingFee(3_000L, 2_000L, 0, true)),
                Arguments.of(Named.of("앞에서 배송비를 뺀 묶음을 끝까지 취소하면 뺀 배송비도 돌려준다", refund(A, 30_000L, 1)),
                        bundle(0, 3_000L, THRESHOLD, item(A, 30_000L, 1), item(B, 30_000L, 1)),
                        List.of(new CancelledQuantity(B, 1)), BASE_FEE, new RefundShippingFee(3_000L, 3_000L, 0, true)),
                Arguments.of(Named.of("취소 뒤에도 기준 이상이면 0", refund(A, 30_000L, 1)),
                        bundle(0, 0, THRESHOLD, item(A, 30_000L, 1), item(B, 30_000L, 1), item(C, 30_000L, 1)),
                        List.of(), 0L, RefundShippingFee.none()),
                Arguments.of(Named.of("취소 뒤 남은 금액이 기준과 같으면 무료배송이 유지되어 0", refund(A, 20_000L, 1)),
                        bundle(0, 0, THRESHOLD, item(A, 20_000L, 1), item(B, 50_000L, 1)),
                        List.of(), 0L, RefundShippingFee.none()),
                Arguments.of(Named.of("일부 취소로 기준 아래로 처음 내려가면 기본 배송비를 뺀다", refund(B, 30_000L, 1)),
                        bundle(0, 0, THRESHOLD, item(A, 30_000L, 1), item(B, 30_000L, 1)),
                        List.of(), 0L, RefundShippingFee.deduct(BASE_FEE)),
                Arguments.of(Named.of("3개 중 2개 취소로 기준이 깨지면 기본 배송비를 뺀다", refund(A, 20_000L, 2)),
                        bundle(0, 0, THRESHOLD, item(A, 20_000L, 3)),
                        List.of(), 0L, RefundShippingFee.deduct(BASE_FEE)),
                Arguments.of(Named.of("처리 중인 앞선 취소까지 합쳐 기준이 처음 깨지면 뺀다", refund(A, 30_000L, 1)),
                        bundle(0, 0, THRESHOLD, item(A, 30_000L, 1), item(B, 30_000L, 1), item(C, 30_000L, 1)),
                        List.of(new CancelledQuantity(B, 1)), 0L, RefundShippingFee.deduct(BASE_FEE)),
                Arguments.of(Named.of("처음부터 기준 아래 묶음의 일부 취소는 0", refund(A, 20_000L, 1)),
                        bundle(3_000L, 0, THRESHOLD, item(A, 20_000L, 1), item(B, 20_000L, 1)),
                        List.of(), 0L, RefundShippingFee.none()),
                // 60,000원 중 B 취소로 40,000원이 되며 이미 3,000원을 뺐다. C를 더 취소해도 두 번 빼지 않는다
                Arguments.of(Named.of("이미 앞에서 뺀 묶음의 다음 일부 취소는 더 빼지 않는다", refund(C, 20_000L, 1)),
                        bundle(0, 0, THRESHOLD, item(A, 20_000L, 1), item(B, 20_000L, 1), item(C, 20_000L, 1)),
                        List.of(new CancelledQuantity(B, 1)), BASE_FEE, RefundShippingFee.none()),
                Arguments.of(Named.of("무료배송 기준이 없으면 일부 취소는 0", refund(B, 30_000L, 1)),
                        bundle(3_000L, 0, null, item(A, 30_000L, 1), item(B, 30_000L, 1)),
                        List.of(), 0L, RefundShippingFee.none()),
                Arguments.of(Named.of("무료배송 기준이 없어도 전부 취소면 배송비를 돌려준다", refund(A, 30_000L, 1)),
                        bundle(3_000L, 0, null, item(A, 30_000L, 1)),
                        List.of(), 0L, RefundShippingFee.refund(3_000L, 0)),
                Arguments.of(Named.of("취소 금액이 기본 배송비와 같으면 0원 환불로 받는다", refund(B, 3_000L, 1)),
                        bundle(0, 0, THRESHOLD, item(A, 47_000L, 1), item(B, 3_000L, 1)),
                        List.of(), 0L, RefundShippingFee.deduct(BASE_FEE)),
                Arguments.of(Named.of("취소 금액이 기본 배송비보다 작으면 받을 수 없다", refund(B, 1_000L, 1)),
                        bundle(0, 0, THRESHOLD, item(A, 49_500L, 1), item(B, 1_000L, 1)),
                        List.of(), 0L, RefundShippingFee.notAllowed()));
    }

    @Test
    void 묶음에_이번_주문_상품이_없으면_예외를_던진다() {
        // given
        BundleShipping bundle = bundle(0, 0, THRESHOLD, item(A, 30_000L, 1));

        // when & then
        assertThatThrownBy(() -> RefundShippingFeePolicy.decide(refund(B, 30_000L, 1), bundle, List.of(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 묶음에 환불할 주문 상품이 없습니다. orderItemId=102");
    }

    @Test
    void 묶음에_없는_상품의_진행_중_환불이_있으면_예외를_던진다() {
        // given
        BundleShipping bundle = bundle(0, 0, THRESHOLD, item(A, 30_000L, 1), item(B, 30_000L, 1));

        // when & then
        assertThatThrownBy(() -> RefundShippingFeePolicy.decide(refund(A, 30_000L, 1), bundle,
                List.of(new CancelledQuantity(C, 1)), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 묶음에 없는 상품의 진행 중 환불이 있습니다. orderItemId=103");
    }

    @Test
    void 취소_개수_합이_주문_개수를_넘으면_예외를_던진다() {
        // given
        BundleShipping bundle = bundle(0, 0, THRESHOLD, item(A, 10_000L, 2), item(B, 30_000L, 1));

        // when & then
        assertThatThrownBy(() -> RefundShippingFeePolicy.decide(refund(A, 10_000L, 2), bundle,
                List.of(new CancelledQuantity(A, 1)), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("취소 개수 합이 주문 개수를 넘습니다. orderItemId=101");
    }

    private static Refund refund(Long orderItemId, long unitPrice, int quantity) {
        return Refund.request(10L, orderItemId, quantity, 1L, 5L, 55L, "상품", "옵션", unitPrice, null);
    }

    private static BundleShipping bundle(long shippingFee, long remoteAreaFee, Long threshold, BundleItem... items) {
        return new BundleShipping(shippingFee, remoteAreaFee, BASE_FEE, threshold, List.of(items));
    }

    private static BundleItem item(Long orderItemId, long unitPrice, int orderedQuantity) {
        return new BundleItem(orderItemId, unitPrice, orderedQuantity);
    }
}
