package com.ok.order.order.adapter.out.delivery.dto;

import com.ok.order.order.domain.SellerShippingFee;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerShippingFeeResponseTest {

    @Test
    void 배송_모듈_응답의_금액을_이름이_다른_필드까지_제자리에_옮긴다() {
        // given: 네 금액을 모두 다르게 둬 자리가 바뀌면 드러나게 한다
        SellerShippingFeeResponse response = new SellerShippingFeeResponse(100L, 3_000L, 50_000L, 2_500L, 4_000L);

        // when
        SellerShippingFee fee = response.toSellerShippingFee();

        // then: 배송 모듈의 regionSurcharge가 주문의 remoteAreaFee가 된다
        assertSoftly(softly -> {
            softly.assertThat(fee.baseShippingFee()).isEqualTo(3_000L);
            softly.assertThat(fee.freeShippingThreshold()).isEqualTo(50_000L);
            softly.assertThat(fee.shippingFee()).isEqualTo(2_500L);
            softly.assertThat(fee.remoteAreaFee()).isEqualTo(4_000L);
        });
    }

    @Test
    void 무료배송_기준이_없으면_null_그대로_옮긴다() {
        // given
        SellerShippingFeeResponse response = new SellerShippingFeeResponse(100L, 3_000L, null, 3_000L, 0L);

        // when
        SellerShippingFee fee = response.toSellerShippingFee();

        // then
        assertThat(fee.freeShippingThreshold()).isNull();
    }
}
