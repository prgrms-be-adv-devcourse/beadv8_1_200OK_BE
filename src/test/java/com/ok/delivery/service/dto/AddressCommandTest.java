package com.ok.delivery.service.dto;

import com.ok.delivery.domain.Address;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class AddressCommandTest {

    @Test
    void Address_값_객체로_변환하면_연락처는_숫자만_남는다() {
        // given
        AddressCommand sut = new AddressCommand(
                "홍길동",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101동 202호",
                "문 앞에 놓아주세요"
        );

        // when
        Address address = sut.toAddress();

        // then
        assertThat(address).isNotNull();
        assertSoftly(
                softly -> {
                    softly.assertThat(address.getRecipientName()).isEqualTo("홍길동");
                    softly.assertThat(address.getRecipientPhone()).isEqualTo("01012345678");
                    softly.assertThat(address.getPostalCode()).isEqualTo("06236");
                    softly.assertThat(address.getAddress()).isEqualTo("서울시 강남구 테헤란로 1");
                    softly.assertThat(address.getAddressDetail()).isEqualTo("101동 202호");
                    softly.assertThat(address.getDeliveryNote()).isEqualTo("문 앞에 놓아주세요");
                }
        );
    }
}
