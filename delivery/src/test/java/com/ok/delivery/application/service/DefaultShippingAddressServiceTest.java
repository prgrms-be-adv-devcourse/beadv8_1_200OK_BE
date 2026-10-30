package com.ok.delivery.application.service;

import com.ok.delivery.adapter.out.persistence.DefaultShippingAddressRepository;
import com.ok.delivery.application.dto.AddressResult;
import com.ok.delivery.domain.DefaultShippingAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.ok.delivery.fixture.ShipmentFixture.address;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddress;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddressCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class DefaultShippingAddressServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    DefaultShippingAddressRepository defaultShippingAddressRepository;

    DefaultShippingAddressService defaultShippingAddressService;

    @BeforeEach
    void setUp() {
        defaultShippingAddressService = new DefaultShippingAddressService(defaultShippingAddressRepository);
    }

    @Test
    void 기본_배송지가_없으면_빈_값을_돌려준다() {
        // given
        given(defaultShippingAddressRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        // when
        Optional<AddressResult> sut = defaultShippingAddressService.getDefaultAddress(MEMBER_ID);

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 기본_배송지가_없으면_새로_저장한다() {
        // given
        given(defaultShippingAddressRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(defaultShippingAddressRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        // when
        AddressResult sut = defaultShippingAddressService.saveDefaultAddress(MEMBER_ID, otherAddressCommand());

        // then
        ArgumentCaptor<DefaultShippingAddress> captor = ArgumentCaptor.forClass(DefaultShippingAddress.class);
        then(defaultShippingAddressRepository).should().save(captor.capture());
        assertThat(captor.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(captor.getValue().getMemberId()).isEqualTo(MEMBER_ID);
            softly.assertThat(captor.getValue().getAddress()).isEqualTo(otherAddress());
            softly.assertThat(sut).isEqualTo(AddressResult.from(otherAddress()));
        });
    }

    @Test
    void 기본_배송지가_있으면_새로_저장하지_않고_배송지를_바꾼다() {
        // given
        DefaultShippingAddress existing = DefaultShippingAddress.create(MEMBER_ID, address());
        given(defaultShippingAddressRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(existing));

        // when
        AddressResult sut = defaultShippingAddressService.saveDefaultAddress(MEMBER_ID, otherAddressCommand());

        // then
        then(defaultShippingAddressRepository).should(never()).save(any());
        assertSoftly(softly -> {
            softly.assertThat(existing.getAddress()).isEqualTo(otherAddress());
            softly.assertThat(sut).isEqualTo(AddressResult.from(otherAddress()));
        });
    }

    @Test
    void 조회할_때_회원_ID가_없으면_예외() {
        // when & then
        assertThatThrownBy(() -> defaultShippingAddressService.getDefaultAddress(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("회원 ID는 필수입니다.");
    }

    @Test
    void 저장할_때_회원_ID가_없으면_예외() {
        // when & then
        assertThatThrownBy(() -> defaultShippingAddressService.saveDefaultAddress(null, otherAddressCommand()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("회원 ID는 필수입니다.");
    }
}
