package com.ok.delivery.adapter.out.persistence;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway로 적재한 초기 데이터의 그룹별 운임·우편번호 건수·소요일 기본값과 대표 우편번호를 확인한다.
 */
class RegionSeedDataIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 지역_그룹별_추가_운임이_표와_일치한다() {
        // when
        Map<String, Integer> sut = jdbcTemplate.query("SELECT code, surcharge FROM region_group", rs -> {
            Map<String, Integer> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("code"), rs.getInt("surcharge"));
            }
            return result;
        });

        // then
        assertThat(sut).containsOnly(
                Map.entry("JEJU_MAIN", 3_000),
                Map.entry("JEJU_ISLANDS", 5_000),
                Map.entry("ULLEUNG", 5_000),
                Map.entry("WEST_SEA_ISLANDS", 5_000),
                Map.entry("ETC_ISLANDS", 3_000),
                Map.entry("MOUNTAIN", 0));
    }

    @Test
    void 일반_지역은_지역_그룹_행이_없다() {
        // when
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM region_group WHERE code = 'GENERAL'", Integer.class);

        // then
        assertThat(count).isZero();
    }

    @Test
    void 그룹별_우편번호_건수가_출처_대조_결과와_일치한다() {
        // when
        Map<String, Integer> sut = jdbcTemplate.query(
                "SELECT region_group, COUNT(*) AS cnt FROM remote_area_postal_code GROUP BY region_group", rs -> {
                    Map<String, Integer> result = new HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("region_group"), rs.getInt("cnt"));
                    }
                    return result;
                });

        // then
        // 강원·경북 산간(MOUNTAIN)은 근거 자료가 없어 우편번호를 넣지 않았다
        assertThat(sut).containsOnly(
                Map.entry("JEJU_MAIN", 506),
                Map.entry("JEJU_ISLANDS", 5),
                Map.entry("ULLEUNG", 41),
                Map.entry("WEST_SEA_ISLANDS", 10),
                Map.entry("ETC_ISLANDS", 152));
    }

    @Test
    void 그룹별_추가_소요일은_기본값이다() {
        // when
        List<String> sut = jdbcTemplate.queryForList(
                "SELECT DISTINCT CONCAT(region_group, ':', extra_delivery_days) FROM remote_area_postal_code", String.class);

        // then
        assertThat(sut.stream().collect(Collectors.toSet())).containsExactlyInAnyOrder(
                "JEJU_MAIN:1", "JEJU_ISLANDS:2", "ULLEUNG:2", "WEST_SEA_ISLANDS:2", "ETC_ISLANDS:2");
    }

    @Test
    void 대표_우편번호가_그룹에_속한다() {
        // when
        Map<String, String> sut = jdbcTemplate.query(
                "SELECT postal_code, region_group FROM remote_area_postal_code WHERE postal_code IN ('63100', '63000', '63365', '40200', '23100', '58800')",
                rs -> {
                    Map<String, String> result = new HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("postal_code"), rs.getString("region_group"));
                    }
                    return result;
                });

        // then
        assertThat(sut).containsOnly(
                Map.entry("63000", "JEJU_ISLANDS"),
                Map.entry("63365", "JEJU_ISLANDS"),
                Map.entry("40200", "ULLEUNG"),
                Map.entry("23100", "WEST_SEA_ISLANDS"),
                Map.entry("58800", "ETC_ISLANDS"),
                Map.entry("63100", "JEJU_MAIN"));
    }

    @Test
    void 시드_우편번호는_모두_삭제되지_않은_상태다() {
        // when
        Integer sut = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM remote_area_postal_code WHERE deleted_at IS NOT NULL", Integer.class);

        // then
        assertThat(sut).isZero();
    }
}
