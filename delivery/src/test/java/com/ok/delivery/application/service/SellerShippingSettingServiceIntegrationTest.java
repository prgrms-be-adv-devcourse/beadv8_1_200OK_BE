package com.ok.delivery.application.service;

import com.ok.delivery.adapter.out.persistence.SellerShippingProfileRepository;
import com.ok.delivery.domain.SellerShippingProfile;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerShippingSettingServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    SellerShippingSettingService sellerShippingSettingService;

    @Autowired
    SellerShippingProfileRepository sellerShippingProfileRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM seller_shipping_profile");
    }

    @Test
    void 배송_설정을_등록하지_않은_판매자는_설정_파일의_기본값을_쓴다() {
        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(10L));

        // then
        assertThat(sut.get(10L)).isEqualTo(new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0)));
    }

    @Test
    void 저장한_판매자는_저장값을_쓰고_무료배송_기준의_null과_0을_구분한다() {
        // given
        SellerShippingSetting settingA = new SellerShippingSetting(5_000, null, LocalTime.of(15, 0));
        SellerShippingSetting settingB = new SellerShippingSetting(2_500, 0, LocalTime.of(10, 0));
        sellerShippingProfileRepository.save(SellerShippingProfile.create(10L, settingA));
        sellerShippingProfileRepository.save(SellerShippingProfile.create(20L, settingB));

        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(10L, 20L, 30L));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get(10L)).isEqualTo(settingA);
            softly.assertThat(sut.get(20L)).isEqualTo(settingB);
            softly.assertThat(sut.get(30L)).isEqualTo(new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0)));
        });
    }

    @Test
    void 같은_판매자의_배송_설정을_두_번_저장하면_예외가_발생한다() {
        // given
        SellerShippingSetting setting = new SellerShippingSetting(5_000, null, LocalTime.of(15, 0));
        sellerShippingProfileRepository.saveAndFlush(SellerShippingProfile.create(10L, setting));

        // when & then
        assertThatThrownBy(() -> sellerShippingProfileRepository.saveAndFlush(SellerShippingProfile.create(10L, setting)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
