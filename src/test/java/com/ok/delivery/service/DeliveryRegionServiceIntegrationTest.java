package com.ok.delivery.service;

import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.repository.RemoteAreaPostalCodeRepository;
import com.ok.delivery.service.dto.RegionResult;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DeliveryRegionServiceIntegrationTest extends AbstractIntegrationTest {

    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99999";

    @Autowired
    DeliveryRegionService deliveryRegionService;

    @Autowired
    RemoteAreaPostalCodeRepository postalCodeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        postalCodeRepository.deleteById(TEST_POSTAL_CODE);
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
}
