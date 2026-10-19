package com.ok.delivery.service;

import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SellerShippingSettingServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    SellerShippingSettingService sellerShippingSettingService;

    @Test
    void 배송_설정을_등록하지_않은_판매자는_설정_파일의_기본값을_쓴다() {
        // when
        Map<Long, SellerShippingSetting> sut = sellerShippingSettingService.getSettings(List.of(10L));

        // then
        assertThat(sut.get(10L)).isEqualTo(new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0)));
    }
}
