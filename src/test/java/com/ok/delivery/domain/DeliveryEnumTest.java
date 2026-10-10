package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryEnumTest {

    @Test
    void ShipmentStatus는_명세의_5개_상태를_가진다() {
        // given
        List<String> expected = List.of("PREPARING", "SHIPPING", "DELIVERED", "COMPLETED", "CANCELLED");

        // when
        List<String> names = Arrays.stream(ShipmentStatus.values()).map(Enum::name).toList();

        // then
        assertThat(names).containsExactlyElementsOf(expected);
    }

    @Test
    void AddressChangeStatus는_명세의_3개_상태를_가진다() {
        // given
        List<String> expected = List.of("PENDING", "APPROVED", "REJECTED");

        // when
        List<String> names = Arrays.stream(AddressChangeStatus.values()).map(Enum::name).toList();

        // then
        assertThat(names).containsExactlyElementsOf(expected);
    }
}
