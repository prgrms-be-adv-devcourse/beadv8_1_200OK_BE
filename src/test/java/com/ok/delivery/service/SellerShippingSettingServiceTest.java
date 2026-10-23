package com.ok.delivery.service;

import com.ok.delivery.config.DefaultShippingProperties;
import com.ok.delivery.domain.SellerShippingSetting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerShippingSettingServiceTest {

    private static final DefaultShippingProperties DEFAULT_PROPERTIES =
            new DefaultShippingProperties(3_000, 50_000, LocalTime.of(13, 0));

    // 설정 값은 record라서 mock 대신 실제 값으로 넣는다
    SellerShippingSettingService sellerShippingSettingService = new SellerShippingSettingService(DEFAULT_PROPERTIES);

    @Test
    void 모든_판매자에게_기본값을_채운다() {
        // given
        SellerShippingSetting expected = DEFAULT_PROPERTIES.toSetting();

        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(10L, 20L));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut).containsOnlyKeys(10L, 20L);
            softly.assertThat(sut.get(10L)).isEqualTo(expected);
            softly.assertThat(sut.get(20L)).isEqualTo(expected);
        });
    }

    @Test
    void 같은_판매자_ID가_여러_번_와도_한_번만_담고_요청_순서를_유지한다() {
        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(30L, 10L, 30L, 20L));

        // then
        assertThat(sut.keySet()).containsExactly(30L, 10L, 20L);
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

    @Test
    void 판매자_한_명을_조회하면_기본값을_돌려준다() {
        // when
        SellerShippingSetting sut = sellerShippingSettingService.getSetting(10L);

        // then
        assertThat(sut).isEqualTo(DEFAULT_PROPERTIES.toSetting());
    }

    @Test
    void 판매자_한_명을_조회할_때_ID가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> sellerShippingSettingService.getSetting(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자 ID는 필수입니다.");
    }
}
