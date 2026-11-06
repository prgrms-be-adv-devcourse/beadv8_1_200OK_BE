package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.dto.SellerItemAmount;
import com.ok.delivery.application.dto.SellerShippingFeeResult;
import com.ok.delivery.application.dto.ShippingFeeQuoteQuery;
import com.ok.delivery.application.dto.ShippingFeesResult;
import com.ok.delivery.domain.SellerShippingSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ShippingFeesServiceTest {

    private static final String POSTAL_CODE = "63100";
    private static final SellerShippingSetting DEFAULT_SETTING = new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0));

    @Mock
    DeliveryRegionService deliveryRegionService;

    @Mock
    SellerShippingSettingService sellerShippingSettingService;

    ShippingFeesService sut;

    @BeforeEach
    void setUp() {
        sut = new ShippingFeesService(deliveryRegionService, sellerShippingSettingService);
    }

    @Test
    void 무료배송_기준_이상이면_기본_배송비가_0원이고_설정_값과_추가_운임은_그대로_돌려준다() {
        // given
        given(deliveryRegionService.resolve(POSTAL_CODE)).willReturn(RegionResult.general());
        given(sellerShippingSettingService.getSettings(List.of(10L, 20L)))
                .willReturn(Map.of(10L, DEFAULT_SETTING, 20L, DEFAULT_SETTING));
        ShippingFeeQuoteQuery query = new ShippingFeeQuoteQuery(POSTAL_CODE, List.of(
                new SellerItemAmount(10L, 49_999), new SellerItemAmount(20L, 50_000)));

        // when
        ShippingFeesResult result = sut.calculate(query);

        // then
        assertThat(result).isNotNull();
        assertThat(result.sellers()).containsExactly(
                new SellerShippingFeeResult(10L, 3_000, 50_000, 3_000, 0),
                new SellerShippingFeeResult(20L, 3_000, 50_000, 0, 0));
    }

    @Test
    void 도서산간이면_무료배송이어도_판매자마다_추가_운임이_붙는다() {
        // given
        RegionResult jeju = new RegionResult("JEJU_MAIN", 3_000, 1);
        given(deliveryRegionService.resolve(POSTAL_CODE)).willReturn(jeju);
        given(sellerShippingSettingService.getSettings(List.of(10L)))
                .willReturn(Map.of(10L, DEFAULT_SETTING));
        ShippingFeeQuoteQuery query = new ShippingFeeQuoteQuery(POSTAL_CODE, List.of(new SellerItemAmount(10L, 50_000)));

        // when
        ShippingFeesResult result = sut.calculate(query);

        // then
        assertThat(result.sellers()).containsExactly(new SellerShippingFeeResult(10L, 3_000, 50_000, 0, 3_000));
    }

    @Test
    void 무료배송_기준이_없으면_금액이_커도_기본_배송비를_받고_기준은_null로_돌려준다() {
        // given
        given(deliveryRegionService.resolve(POSTAL_CODE)).willReturn(RegionResult.general());
        given(sellerShippingSettingService.getSettings(List.of(10L)))
                .willReturn(Map.of(10L, new SellerShippingSetting(5_000, null, LocalTime.of(13, 0))));
        ShippingFeeQuoteQuery query = new ShippingFeeQuoteQuery(POSTAL_CODE, List.of(new SellerItemAmount(10L, 10_000_000)));

        // when
        ShippingFeesResult result = sut.calculate(query);

        // then
        assertThat(result.sellers()).containsExactly(new SellerShippingFeeResult(10L, 5_000, null, 5_000, 0));
    }
}
