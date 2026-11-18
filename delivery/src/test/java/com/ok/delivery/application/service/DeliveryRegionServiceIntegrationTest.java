package com.ok.delivery.application.service;

import com.ok.delivery.adapter.out.persistence.RemoteAreaPostalCodeJpaRepository;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DeliveryRegionServiceIntegrationTest extends AbstractIntegrationTest {

    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99999";

    @Autowired
    DeliveryRegionService deliveryRegionService;

    @Autowired
    RemoteAreaPostalCodeJpaRepository postalCodeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        postalCodeRepository.deleteById(TEST_POSTAL_CODE);
        jdbcTemplate.update("DELETE FROM seller_origin_postal_code");
    }

    @ParameterizedTest
    @MethodSource("그룹별_대표_우편번호")
    void 초기_데이터의_우편번호를_그룹의_운임과_소요일로_판정한다(String postalCode, String regionGroup, int surcharge, int extraDeliveryDays) {
        // when
        RegionResult sut = deliveryRegionService.resolve(postalCode);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.regionGroup()).isEqualTo(regionGroup);
            softly.assertThat(sut.surcharge()).isEqualTo(surcharge);
            softly.assertThat(sut.extraDeliveryDays()).isEqualTo(extraDeliveryDays);
        });
    }

    static Stream<Object[]> 그룹별_대표_우편번호() {
        return Stream.of(
                new Object[]{"63100", "JEJU_MAIN", 3_000, 1},
                new Object[]{"63000", "JEJU_ISLANDS", 5_000, 2},
                new Object[]{"40200", "ULLEUNG", 5_000, 2},
                new Object[]{"23100", "WEST_SEA_ISLANDS", 5_000, 2},
                new Object[]{"58800", "ETC_ISLANDS", 3_000, 2});
    }

    @Test
    void 등록되지_않은_우편번호는_일반_지역이다() {
        // when
        RegionResult sut = deliveryRegionService.resolve("06236");

        // then
        assertThat(sut).isEqualTo(RegionResult.general());
    }

    @Test
    void 우편번호를_등록하면_판정이_바뀐다() {
        // given
        assertThat(deliveryRegionService.resolve(TEST_POSTAL_CODE)).isEqualTo(RegionResult.general());

        // when
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "MOUNTAIN", 1));
        RegionResult sut = deliveryRegionService.resolve(TEST_POSTAL_CODE);

        // then
        assertThat(sut).isEqualTo(new RegionResult("MOUNTAIN", 0, 1));
    }

    @Test
    void 삭제된_우편번호는_일반_지역으로_판정한다() {
        // given
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "MOUNTAIN", 1));
        jdbcTemplate.update("UPDATE remote_area_postal_code SET deleted_at = NOW(6) WHERE postal_code = ?", TEST_POSTAL_CODE);

        // when
        RegionResult sut = deliveryRegionService.resolve(TEST_POSTAL_CODE);

        // then
        assertThat(sut).isEqualTo(RegionResult.general());
    }

    // 기대값은 마이그레이션 시드(63100·63200 JEJU_MAIN 3,000원, 63000 JEJU_ISLANDS 5,000원)에 의존한다
    @Test
    void 제주_본섬으로_보낼_때_출고지가_제주_본섬인_판매자만_추가_운임이_0원이다() {
        // given: 10 제주 본섬, 20 제주 부속 도서, 30 서울, 40 출고지 없음
        saveOrigin(10L, "63200");
        saveOrigin(20L, "63000");
        saveOrigin(30L, "06236");
        RegionResult destination = deliveryRegionService.resolve("63100");

        // when
        Map<Long, Integer> sut = deliveryRegionService.resolveSurcharges(destination, List.of(10L, 20L, 30L, 40L));

        // then
        assertThat(sut).containsExactly(
                Map.entry(10L, 0), Map.entry(20L, 3_000), Map.entry(30L, 3_000), Map.entry(40L, 3_000));
    }

    @Test
    void 제주_본섬에서_부속_도서로_보내면_추가_운임을_그대로_받는다() {
        // given
        saveOrigin(10L, "63200");
        RegionResult destination = deliveryRegionService.resolve("63000");

        // when
        int sut = deliveryRegionService.resolveSurcharge(destination, 10L);

        // then
        assertThat(sut).isEqualTo(5_000);
    }

    @Test
    void 출고지_우편번호가_도서산간에서_삭제되면_면제하지_않는다() {
        // given
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "JEJU_MAIN", 1));
        jdbcTemplate.update("UPDATE remote_area_postal_code SET deleted_at = NOW(6) WHERE postal_code = ?", TEST_POSTAL_CODE);
        saveOrigin(10L, TEST_POSTAL_CODE);
        RegionResult destination = deliveryRegionService.resolve("63100");

        // when
        int sut = deliveryRegionService.resolveSurcharge(destination, 10L);

        // then
        assertThat(sut).isEqualTo(3_000);
    }

    private void saveOrigin(Long sellerId, String postalCode) {
        jdbcTemplate.update("""
                INSERT INTO seller_origin_postal_code (seller_id, postal_code, source_occurred_at, created_at, updated_at)
                VALUES (?, ?, NOW(6), NOW(6), NOW(6))
                """, sellerId, postalCode);
    }
}
