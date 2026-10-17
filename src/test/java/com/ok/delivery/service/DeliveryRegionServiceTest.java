package com.ok.delivery.service;

import com.ok.delivery.domain.RegionGroup;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.repository.RegionGroupRepository;
import com.ok.delivery.repository.RemoteAreaPostalCodeRepository;
import com.ok.delivery.service.dto.RegionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class DeliveryRegionServiceTest {

    @Mock
    RemoteAreaPostalCodeRepository postalCodeRepository;

    @Mock
    RegionGroupRepository regionGroupRepository;

    @InjectMocks
    DeliveryRegionService deliveryRegionService;

    @Test
    void 등록되지_않은_우편번호는_일반_지역으로_판정한다() {
        // given
        given(postalCodeRepository.findByPostalCodeAndDeletedAtIsNull("06236")).willReturn(Optional.empty());

        // when
        RegionResult sut = deliveryRegionService.resolve("06236");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.regionGroup()).isEqualTo("GENERAL");
            softly.assertThat(sut.surcharge()).isZero();
            softly.assertThat(sut.extraDeliveryDays()).isZero();
        });
    }

    @Test
    void 등록된_우편번호는_그룹의_추가_운임과_우편번호의_추가_소요일로_판정한다() {
        // given
        given(postalCodeRepository.findByPostalCodeAndDeletedAtIsNull("40200")).willReturn(Optional.of(RemoteAreaPostalCode.of("40200", "ULLEUNG", 2)));
        given(regionGroupRepository.findById("ULLEUNG")).willReturn(Optional.of(
                RegionGroup.builder().code("ULLEUNG").name("울릉도").surcharge(5_000).build()));

        // when
        RegionResult sut = deliveryRegionService.resolve("40200");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.regionGroup()).isEqualTo("ULLEUNG");
            softly.assertThat(sut.surcharge()).isEqualTo(5_000);
            softly.assertThat(sut.extraDeliveryDays()).isEqualTo(2);
        });
    }

    @Test
    void 지역_그룹_행이_없으면_일반_지역으로_숨기지_않고_실패한다() {
        // given
        given(postalCodeRepository.findByPostalCodeAndDeletedAtIsNull("40200")).willReturn(Optional.of(RemoteAreaPostalCode.of("40200", "ULLEUNG", 2)));
        given(regionGroupRepository.findById("ULLEUNG")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> deliveryRegionService.resolve("40200"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 우편번호가_null이면_일반_지역으로_숨기지_않고_실패한다() {
        // when & then
        assertThatThrownBy(() -> deliveryRegionService.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("우편번호는 필수입니다.");
    }
}
