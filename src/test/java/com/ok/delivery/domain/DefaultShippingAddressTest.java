package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;

import static com.ok.delivery.fixture.ShipmentFixture.address;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DefaultShippingAddressTest {

    @Test
    void 회원과_배송지로_기본_배송지를_만든다() {
        // when
        DefaultShippingAddress sut = DefaultShippingAddress.create(1L, address());

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMemberId()).isEqualTo(1L);
            softly.assertThat(sut.getAddress()).isEqualTo(address());
        });
    }

    @Test
    void 회원_ID가_없으면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> DefaultShippingAddress.create(null, address()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memberId는 필수입니다.");
    }

    @Test
    void 배송지가_없으면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> DefaultShippingAddress.create(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송지는 필수입니다.");
    }

    @Test
    void 배송지를_새_배송지로_바꾼다() {
        // given
        DefaultShippingAddress sut = DefaultShippingAddress.create(1L, address());

        // when
        sut.changeAddress(otherAddress());

        // then
        assertThat(sut.getAddress()).isEqualTo(otherAddress());
    }

    @Test
    void 배송지를_없는_값으로_바꿀_수_없다() {
        // given
        DefaultShippingAddress sut = DefaultShippingAddress.create(1L, address());

        // when & then
        assertThatThrownBy(() -> sut.changeAddress(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송지는 필수입니다.");
    }
}
