package com.ok.delivery.service;

import com.ok.delivery.config.DefaultShippingProperties;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.product.SellerShippingPolicy;
import com.ok.product.SellerShippingPolicyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SellerShippingSettingServiceTest {

    private static final DefaultShippingProperties DEFAULT_PROPERTIES =
            new DefaultShippingProperties(3_000, 50_000, LocalTime.of(13, 0));

    @Mock
    SellerShippingPolicyProvider sellerShippingPolicyProvider;

    SellerShippingSettingService sellerShippingSettingService;

    @BeforeEach
    void setUp() {
        // 설정 값은 record라서 mock 대신 실제 값으로 넣는다
        sellerShippingSettingService = new SellerShippingSettingService(sellerShippingPolicyProvider, DEFAULT_PROPERTIES);
    }

    @Test
    void 설정이_있는_판매자는_등록한_설정을_쓰고_없는_판매자는_기본값을_쓴다() {
        // given
        given(sellerShippingPolicyProvider.findBySellerIds(Set.of(10L, 20L)))
                .willReturn(Map.of(10L, new SellerShippingPolicy(2_500, 30_000, LocalTime.of(15, 0))));

        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(10L, 20L));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut).containsOnlyKeys(10L, 20L);
            softly.assertThat(sut.get(10L)).isEqualTo(new SellerShippingSetting(2_500, 30_000, LocalTime.of(15, 0)));
            softly.assertThat(sut.get(20L)).isEqualTo(new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0)));
        });
    }

    @Test
    void 판매자가_무료배송_기준을_두지_않으면_기본값으로_채우지_않고_무료배송_없음으로_둔다() {
        // given
        given(sellerShippingPolicyProvider.findBySellerIds(Set.of(10L)))
                .willReturn(Map.of(10L, new SellerShippingPolicy(3_000, null, LocalTime.of(13, 0))));

        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(10L));

        // then
        assertThat(sut.get(10L).freeShippingThreshold()).isNull();
    }

    @Test
    void 같은_판매자_ID가_여러_번_와도_한_번만_조회한다() {
        // given
        given(sellerShippingPolicyProvider.findBySellerIds(Set.of(10L))).willReturn(Map.of());

        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(10L, 10L));

        // then
        assertThat(sut).containsOnlyKeys(10L);
    }

    @Test
    void 조회한_설정의_금액이_음수면_예외가_발생한다() {
        // given
        given(sellerShippingPolicyProvider.findBySellerIds(Set.of(10L)))
                .willReturn(Map.of(10L, new SellerShippingPolicy(-1, 50_000, LocalTime.of(13, 0))));

        // when & then
        assertThatThrownBy(() -> sellerShippingSettingService.getSettings(List.of(10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송비 금액은 0 이상이어야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("잘못된_판매자_ID_목록")
    void 판매자_ID_목록이_없거나_null인_ID가_있으면_예외가_발생한다(Collection<Long> sellerIds) {
        // when & then
        assertThatThrownBy(() -> sellerShippingSettingService.getSettings(sellerIds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자 ID 목록은 필수이고 null인 ID를 포함할 수 없습니다.");
    }

    static Stream<Collection<Long>> 잘못된_판매자_ID_목록() {
        return Stream.of(null, Arrays.asList(10L, null));
    }
}
