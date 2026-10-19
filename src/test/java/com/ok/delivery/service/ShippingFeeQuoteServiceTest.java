package com.ok.delivery.service;

import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.delivery.domain.WeekendBusinessDayCalendar;
import com.ok.delivery.service.dto.RegionResult;
import com.ok.delivery.service.dto.SellerItemAmount;
import com.ok.delivery.service.dto.SellerShippingQuoteResult;
import com.ok.delivery.service.dto.ShippingFeeQuoteQuery;
import com.ok.delivery.service.dto.ShippingFeeQuoteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ShippingFeeQuoteServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 2026-10-16(금) 12:00. 마감 13:00 판매자는 당일 출고, 마감 11:00 판매자는 다음 영업일(월) 출고
    private static final LocalDateTime FRIDAY_NOON = LocalDateTime.of(2026, 10, 16, 12, 0);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate NEXT_TUESDAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate NEXT_WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate NEXT_THURSDAY = LocalDate.of(2026, 10, 22);

    private static final String POSTAL_CODE = "63100";
    private static final SellerShippingSetting DEFAULT_SETTING = new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0));

    @Mock
    DeliveryRegionService deliveryRegionService;

    @Mock
    SellerShippingSettingService sellerShippingSettingService;

    ShippingFeeQuoteService shippingFeeQuoteService;

    @BeforeEach
    void setUp() {
        // 날짜 계산은 정책 자체를 검증하는 대신 실제 정책으로 서비스의 조합을 확인한다
        Clock clock = Clock.fixed(FRIDAY_NOON.atZone(SEOUL).toInstant(), SEOUL);
        shippingFeeQuoteService = new ShippingFeeQuoteService(
                deliveryRegionService, sellerShippingSettingService,
                new DeliveryDatePolicy(new WeekendBusinessDayCalendar()), clock);
    }

    @Test
    void 일반_지역은_판매자별_무료배송_기준으로_기본_배송비만_붙는다() {
        // given
        given(deliveryRegionService.resolve(POSTAL_CODE)).willReturn(RegionResult.general());
        given(sellerShippingSettingService.getSettings(List.of(10L, 20L)))
                .willReturn(Map.of(10L, DEFAULT_SETTING, 20L, DEFAULT_SETTING));
        ShippingFeeQuoteQuery query = new ShippingFeeQuoteQuery(POSTAL_CODE, List.of(
                new SellerItemAmount(10L, 49_999), new SellerItemAmount(20L, 50_000)));

        // when
        ShippingFeeQuoteResult sut = shippingFeeQuoteService.quote(query);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.regionGroup()).isEqualTo("GENERAL");
            softly.assertThat(sut.sellers()).containsExactly(
                    new SellerShippingQuoteResult(10L, 3_000, 0, 3_000, FRIDAY, NEXT_TUESDAY, NEXT_WEDNESDAY),
                    new SellerShippingQuoteResult(20L, 0, 0, 0, FRIDAY, NEXT_TUESDAY, NEXT_WEDNESDAY));
            softly.assertThat(sut.totalShippingFee()).isEqualTo(3_000);
        });
    }

    @Test
    void 도서산간은_무료배송이어도_판매자마다_추가_운임과_추가_소요일이_붙는다() {
        // given
        given(deliveryRegionService.resolve(POSTAL_CODE)).willReturn(new RegionResult("JEJU_MAIN", 3_000, 1));
        given(sellerShippingSettingService.getSettings(List.of(10L, 20L)))
                .willReturn(Map.of(10L, DEFAULT_SETTING, 20L, DEFAULT_SETTING));
        ShippingFeeQuoteQuery query = new ShippingFeeQuoteQuery(POSTAL_CODE, List.of(
                new SellerItemAmount(10L, 10_000), new SellerItemAmount(20L, 50_000)));

        // when
        ShippingFeeQuoteResult sut = shippingFeeQuoteService.quote(query);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.regionGroup()).isEqualTo("JEJU_MAIN");
            softly.assertThat(sut.sellers()).containsExactly(
                    new SellerShippingQuoteResult(10L, 3_000, 3_000, 6_000, FRIDAY, NEXT_WEDNESDAY, NEXT_THURSDAY),
                    new SellerShippingQuoteResult(20L, 0, 3_000, 3_000, FRIDAY, NEXT_WEDNESDAY, NEXT_THURSDAY));
            softly.assertThat(sut.totalShippingFee()).isEqualTo(9_000);
        });
    }

    @Test
    void 판매자마다_마감_시간이_달라_출고_예정일을_따로_계산한다() {
        // given
        SellerShippingSetting earlyCutoff = new SellerShippingSetting(2_500, null, LocalTime.of(11, 0));
        given(deliveryRegionService.resolve(POSTAL_CODE)).willReturn(RegionResult.general());
        given(sellerShippingSettingService.getSettings(List.of(10L, 20L)))
                .willReturn(Map.of(10L, DEFAULT_SETTING, 20L, earlyCutoff));
        ShippingFeeQuoteQuery query = new ShippingFeeQuoteQuery(POSTAL_CODE, List.of(
                new SellerItemAmount(10L, 10_000), new SellerItemAmount(20L, 100_000)));

        // when
        ShippingFeeQuoteResult sut = shippingFeeQuoteService.quote(query);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.sellers()).containsExactly(
                    new SellerShippingQuoteResult(10L, 3_000, 0, 3_000, FRIDAY, NEXT_TUESDAY, NEXT_WEDNESDAY),
                    // 무료배송 기준이 없는 판매자라 금액이 커도 기본 배송비가 붙는다
                    new SellerShippingQuoteResult(20L, 2_500, 0, 2_500, NEXT_MONDAY, NEXT_WEDNESDAY, NEXT_THURSDAY));
            softly.assertThat(sut.totalShippingFee()).isEqualTo(5_500);
        });
    }
}
