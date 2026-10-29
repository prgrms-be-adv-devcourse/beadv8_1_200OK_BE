package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ok.refund.fixture.RefundFixture.requested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 설계 5장 예시: 같은 판매자 묶음에 양말(101) 3개 + 모자(102) 1개
class RefundBundlePolicyTest {

    private static final Long SOCKS = 101L;
    private static final Long HAT = 102L;
    private static final List<BundleItem> BUNDLE = List.of(new BundleItem(SOCKS, 3), new BundleItem(HAT, 1));

    @Test
    void 일부만_취소하면_전부_취소가_아니다() {
        // given
        Refund target = requested(SOCKS, 1);

        // when
        boolean sut = RefundBundlePolicy.cancelsWholeBundle(target, BUNDLE, List.of());

        // then
        assertThat(sut).isFalse();
    }

    @Test
    void 한_요청으로_묶음_전부를_취소하면_전부_취소다() {
        // given
        Refund target = requested(SOCKS, 1);
        List<BundleItem> socksOnly = List.of(new BundleItem(SOCKS, 1));

        // when
        boolean sut = RefundBundlePolicy.cancelsWholeBundle(target, socksOnly, List.of());

        // then
        assertThat(sut).isTrue();
    }

    @Test
    void 나눠서_취소한_마지막_요청이_전부_취소다() {
        // given: 양말 2개 완료 + 1개 처리 중(합 3), 이번에 모자 1개
        Refund target = requested(HAT, 1);
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(SOCKS, 3L));

        // when
        boolean sut = RefundBundlePolicy.cancelsWholeBundle(target, BUNDLE, cancelled);

        // then
        assertThat(sut).isTrue();
    }

    @Test
    void 이번_상품이_나눠서_마지막으로_채워지면_전부_취소다() {
        // given: 모자 1개·양말 2개 이미 취소, 이번에 양말 1개
        Refund target = requested(SOCKS, 1);
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(HAT, 1L), new CancelledQuantity(SOCKS, 2L));

        // when
        boolean sut = RefundBundlePolicy.cancelsWholeBundle(target, BUNDLE, cancelled);

        // then
        assertThat(sut).isTrue();
    }

    @Test
    void 묶음_밖_상품의_집계는_판단에_넣지_않는다() {
        // given: 다른 묶음 상품(999)이 집계에 섞여 와도 묶음 상품만 본다
        Refund target = requested(HAT, 1);
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(SOCKS, 3L), new CancelledQuantity(999L, 5L));

        // when
        boolean sut = RefundBundlePolicy.cancelsWholeBundle(target, BUNDLE, cancelled);

        // then
        assertThat(sut).isTrue();
    }

    @Test
    void 다른_상품이_덜_취소됐으면_전부_취소가_아니다() {
        // given: 양말 2개만 취소됨, 이번에 모자 1개
        Refund target = requested(HAT, 1);
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(SOCKS, 2L));

        // when
        boolean sut = RefundBundlePolicy.cancelsWholeBundle(target, BUNDLE, cancelled);

        // then
        assertThat(sut).isFalse();
    }

    @Test
    void 이번_요청까지_더해_주문_수량을_넘으면_409() {
        // given: 양말 2개 이미 취소, 이번에 양말 2개
        Refund target = requested(SOCKS, 2);
        List<CancelledQuantity> cancelled = List.of(new CancelledQuantity(SOCKS, 2L));

        // when & then
        assertThatThrownBy(() -> RefundBundlePolicy.cancelsWholeBundle(target, BUNDLE, cancelled))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_QUANTITY_EXCEEDED));
    }

    @Test
    void 묶음_상품_목록이_비어_있으면_판단하지_않는다() {
        // given
        Refund target = requested(SOCKS, 1);

        // when & then
        assertThatThrownBy(() -> RefundBundlePolicy.cancelsWholeBundle(target, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("판매자 묶음 상품 목록이 비어 있습니다.");
    }

    @Test
    void 묶음_상품_목록에_이번_상품이_없으면_판단하지_않는다() {
        // given
        Refund target = requested(SOCKS, 1);
        List<BundleItem> hatOnly = List.of(new BundleItem(HAT, 1));

        // when & then
        assertThatThrownBy(() -> RefundBundlePolicy.cancelsWholeBundle(target, hatOnly, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("판매자 묶음 상품 목록에 취소할 주문 상품이 없습니다. orderItemId=101");
    }

    @Test
    void 묶음_상품의_주문_수량이_1_미만이면_판단하지_않는다() {
        // given
        Refund target = requested(SOCKS, 1);
        List<BundleItem> zeroHat = List.of(new BundleItem(SOCKS, 3), new BundleItem(HAT, 0));

        // when & then
        assertThatThrownBy(() -> RefundBundlePolicy.cancelsWholeBundle(target, zeroHat, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("판매자 묶음 상품의 ID와 주문 수량(1 이상)은 필수입니다.");
    }
}
