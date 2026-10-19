package com.ok.delivery.service.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShippingFeeQuoteQueryTest {

    @Test
    void 같은_판매자가_두_번_있으면_예외() {
        // given
        List<SellerItemAmount> sellers = List.of(new SellerItemAmount(10L, 1_000), new SellerItemAmount(10L, 2_000));

        // when & then
        assertThatThrownBy(() -> new ShippingFeeQuoteQuery("63100", sellers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자가 중복되었습니다. sellerId=10");
    }

    @Test
    void 판매자_목록이_비면_예외() {
        // when & then
        assertThatThrownBy(() -> new ShippingFeeQuoteQuery("63100", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자 목록은 1개 이상이어야 하고 null 항목을 포함할 수 없습니다.");
    }

    @Test
    void 판매자_목록을_복사해_밖에서_바꿔도_영향이_없다() {
        // given
        List<SellerItemAmount> sellers = new ArrayList<>(List.of(new SellerItemAmount(10L, 1_000)));
        ShippingFeeQuoteQuery sut = new ShippingFeeQuoteQuery("63100", sellers);

        // when
        sellers.add(new SellerItemAmount(20L, 2_000));

        // then
        assertThat(sut.sellerIds()).containsExactly(10L);
    }
}
