package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.AddressResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static com.ok.delivery.fixture.ShipmentFixture.otherAddress;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddressCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class DefaultShippingAddressFacadeTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    DefaultShippingAddressService defaultShippingAddressService;

    DefaultShippingAddressFacade defaultShippingAddressFacade;

    @BeforeEach
    void setUp() {
        defaultShippingAddressFacade = new DefaultShippingAddressFacade(defaultShippingAddressService);
    }

    @Test
    void 동시_등록과_겹쳐_유니크_제약에_걸리면_한_번_다시_시도한다() {
        // given
        AddressResult saved = AddressResult.from(otherAddress());
        given(defaultShippingAddressService.saveDefaultAddress(MEMBER_ID, otherAddressCommand()))
                .willThrow(new DataIntegrityViolationException("uk_default_shipping_address_member_id"))
                .willReturn(saved);

        // when
        AddressResult sut = defaultShippingAddressFacade.saveDefaultAddress(MEMBER_ID, otherAddressCommand());

        // then
        assertThat(sut).isEqualTo(saved);
    }

    @Test
    void 다시_시도해도_실패하면_더_시도하지_않고_예외를_그대로_던진다() {
        // given
        given(defaultShippingAddressService.saveDefaultAddress(MEMBER_ID, otherAddressCommand()))
                .willThrow(new DataIntegrityViolationException("uk_default_shipping_address_member_id"));

        // when & then
        assertThatThrownBy(() -> defaultShippingAddressFacade.saveDefaultAddress(MEMBER_ID, otherAddressCommand()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("uk_default_shipping_address_member_id");
        then(defaultShippingAddressService).should(times(2)).saveDefaultAddress(MEMBER_ID, otherAddressCommand());
    }

    @Test
    void 제약_위반이_아닌_예외는_다시_시도하지_않는다() {
        // given
        given(defaultShippingAddressService.saveDefaultAddress(null, otherAddressCommand()))
                .willThrow(new IllegalArgumentException("회원 ID는 필수입니다."));

        // when & then
        assertThatThrownBy(() -> defaultShippingAddressFacade.saveDefaultAddress(null, otherAddressCommand()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("회원 ID는 필수입니다.");
        then(defaultShippingAddressService).should(times(1)).saveDefaultAddress(null, otherAddressCommand());
    }
}
