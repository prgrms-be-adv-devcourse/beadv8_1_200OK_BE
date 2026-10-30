package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.adapter.out.persistence.RemoteAreaPostalCodeRepository;
import com.ok.delivery.application.dto.PostalRegionRegisterCommand;
import com.ok.delivery.application.dto.PostalRegionResult;
import com.ok.delivery.application.dto.PostalRegionUpdateCommand;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DeliveryRegionServiceAdminIntegrationTest extends AbstractIntegrationTest {

    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99997";
    private static final Long ADMIN_ID = 1L;

    @Autowired
    DeliveryRegionService deliveryRegionService;

    @Autowired
    RemoteAreaPostalCodeRepository postalCodeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 이력이 우편번호를 외래키로 참조하므로 이력부터 지운다
        jdbcTemplate.update("DELETE FROM remote_area_postal_code_history WHERE postal_code = ?", TEST_POSTAL_CODE);
        jdbcTemplate.update("DELETE FROM remote_area_postal_code WHERE postal_code = ?", TEST_POSTAL_CODE);
    }

    @Test
    void 새_우편번호를_등록하면_판정에_반영되고_REGISTERED_이력이_남는다() {
        // when
        PostalRegionResult sut = deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);

        // then
        assertThat(sut).isEqualTo(new PostalRegionResult(TEST_POSTAL_CODE, "JEJU_ISLANDS", 2));
        assertSoftly(softly -> {
            softly.assertThat(deliveryRegionService.resolve(TEST_POSTAL_CODE)).isEqualTo(new RegionResult("JEJU_ISLANDS", 5_000, 2));
            softly.assertThat(histories()).containsExactly("REGISTERED JEJU_ISLANDS 2 " + ADMIN_ID);
        });
    }

    @Test
    void 우편번호를_삭제하면_일반_지역이_되고_DELETED_이력에_삭제_시점_값이_남는다() {
        // given
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);

        // when
        deliveryRegionService.delete(TEST_POSTAL_CODE, 2L);

        // then
        RemoteAreaPostalCode sut = postalCodeRepository.findById(TEST_POSTAL_CODE).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(sut.getDeletedAt()).isNotNull();
            softly.assertThat(deliveryRegionService.resolve(TEST_POSTAL_CODE)).isEqualTo(RegionResult.general());
            softly.assertThat(histories()).containsExactly(
                    "REGISTERED JEJU_ISLANDS 2 " + ADMIN_ID,
                    "DELETED JEJU_ISLANDS 2 2");
        });
    }

    @Test
    void 삭제된_우편번호를_다시_등록하면_같은_행을_요청값으로_되살리고_RESTORED_이력이_남는다() {
        // given
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);
        deliveryRegionService.delete(TEST_POSTAL_CODE, ADMIN_ID);

        // when
        PostalRegionResult sut = deliveryRegionService.register(command("ULLEUNG", 3), ADMIN_ID);

        // then
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remote_area_postal_code WHERE postal_code = ?", Integer.class, TEST_POSTAL_CODE);
        assertThat(sut).isEqualTo(new PostalRegionResult(TEST_POSTAL_CODE, "ULLEUNG", 3));
        assertSoftly(softly -> {
            softly.assertThat(rowCount).isEqualTo(1);
            softly.assertThat(deliveryRegionService.resolve(TEST_POSTAL_CODE)).isEqualTo(new RegionResult("ULLEUNG", 5_000, 3));
            softly.assertThat(histories()).containsExactly(
                    "REGISTERED JEJU_ISLANDS 2 " + ADMIN_ID,
                    "DELETED JEJU_ISLANDS 2 " + ADMIN_ID,
                    "RESTORED ULLEUNG 3 " + ADMIN_ID);
        });
    }

    @ParameterizedTest
    @MethodSource("등록할_수_없는_지역_그룹")
    void 없는_지역_그룹이나_일반_지역으로는_등록할_수_없다(String regionGroup) {
        // when & then
        assertThatThrownBy(() -> deliveryRegionService.register(command(regionGroup, 1), ADMIN_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.REGION_GROUP_NOT_FOUND));
        assertSoftly(softly -> {
            softly.assertThat(postalCodeRepository.findById(TEST_POSTAL_CODE)).isEmpty();
            softly.assertThat(histories()).isEmpty();
        });
    }

    static Stream<String> 등록할_수_없는_지역_그룹() {
        return Stream.of("NOT_EXISTS", "GENERAL");
    }

    @Test
    void 삭제된_우편번호를_없는_그룹으로_다시_등록하면_실패하고_삭제_상태가_유지된다() {
        // given
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);
        deliveryRegionService.delete(TEST_POSTAL_CODE, ADMIN_ID);

        // when & then
        assertThatThrownBy(() -> deliveryRegionService.register(command("NOT_EXISTS", 3), ADMIN_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.REGION_GROUP_NOT_FOUND));
        RemoteAreaPostalCode sut = postalCodeRepository.findById(TEST_POSTAL_CODE).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(sut.getDeletedAt()).isNotNull();
            softly.assertThat(sut.getRegionGroup()).isEqualTo("JEJU_ISLANDS");
            softly.assertThat(histories()).hasSize(2);
        });
    }

    @Test
    void 우편번호를_수정하면_바로_판정에_반영되고_UPDATED_이력이_남는다() {
        // given
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);

        // when
        PostalRegionResult sut = deliveryRegionService.update(updateCommand("ULLEUNG", 3), 2L);

        // then
        assertThat(sut).isEqualTo(new PostalRegionResult(TEST_POSTAL_CODE, "ULLEUNG", 3));
        assertSoftly(softly -> {
            softly.assertThat(deliveryRegionService.resolve(TEST_POSTAL_CODE)).isEqualTo(new RegionResult("ULLEUNG", 5_000, 3));
            softly.assertThat(histories()).containsExactly(
                    "REGISTERED JEJU_ISLANDS 2 " + ADMIN_ID,
                    "UPDATED ULLEUNG 3 2");
        });
    }

    @Test
    void 같은_값으로_수정하면_이력이_남지_않는다() {
        // given
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);

        // when
        PostalRegionResult sut = deliveryRegionService.update(updateCommand("JEJU_ISLANDS", 2), ADMIN_ID);

        // then
        assertThat(sut).isEqualTo(new PostalRegionResult(TEST_POSTAL_CODE, "JEJU_ISLANDS", 2));
        assertThat(histories()).containsExactly("REGISTERED JEJU_ISLANDS 2 " + ADMIN_ID);
    }

    @Test
    void 없는_우편번호는_수정할_수_없다() {
        // when & then
        assertThatThrownBy(() -> deliveryRegionService.update(updateCommand("ULLEUNG", 3), ADMIN_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
    }

    @Test
    void 삭제된_우편번호는_수정할_수_없고_삭제_상태가_유지된다() {
        // given
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);
        deliveryRegionService.delete(TEST_POSTAL_CODE, ADMIN_ID);

        // when & then
        assertThatThrownBy(() -> deliveryRegionService.update(updateCommand("ULLEUNG", 3), ADMIN_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
        RemoteAreaPostalCode sut = postalCodeRepository.findById(TEST_POSTAL_CODE).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(sut.getDeletedAt()).isNotNull();
            softly.assertThat(sut.getRegionGroup()).isEqualTo("JEJU_ISLANDS");
            softly.assertThat(histories()).hasSize(2);
        });
    }

    @ParameterizedTest
    @MethodSource("등록할_수_없는_지역_그룹")
    void 없는_지역_그룹이나_일반_지역으로_수정하면_실패하고_기존_값이_유지된다(String regionGroup) {
        // given
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);

        // when & then
        assertThatThrownBy(() -> deliveryRegionService.update(updateCommand(regionGroup, 3), ADMIN_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.REGION_GROUP_NOT_FOUND));
        assertSoftly(softly -> {
            softly.assertThat(deliveryRegionService.resolve(TEST_POSTAL_CODE)).isEqualTo(new RegionResult("JEJU_ISLANDS", 5_000, 2));
            softly.assertThat(histories()).containsExactly("REGISTERED JEJU_ISLANDS 2 " + ADMIN_ID);
        });
    }

    @Test
    void 없는_우편번호는_삭제할_수_없다() {
        // when & then
        assertThatThrownBy(() -> deliveryRegionService.delete(TEST_POSTAL_CODE, ADMIN_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
    }

    @Test
    void 목록은_삭제되지_않은_우편번호만_우편번호_순으로_조회한다() {
        // given
        long seedCount = deliveryRegionService.getPostalRegions(0, 25).getTotalElements();
        deliveryRegionService.register(command("JEJU_ISLANDS", 2), ADMIN_ID);
        long afterRegister = deliveryRegionService.getPostalRegions(0, 25).getTotalElements();
        deliveryRegionService.delete(TEST_POSTAL_CODE, ADMIN_ID);

        // when
        Page<PostalRegionResult> sut = deliveryRegionService.getPostalRegions(0, 25);

        // then
        List<String> postalCodes = sut.getContent().stream().map(PostalRegionResult::postalCode).toList();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(afterRegister).isEqualTo(seedCount + 1);
            softly.assertThat(sut.getTotalElements()).isEqualTo(seedCount);
            softly.assertThat(sut.getContent()).hasSize(25);
            softly.assertThat(postalCodes).isSorted();
        });
    }

    private static PostalRegionRegisterCommand command(String regionGroup, int extraDeliveryDays) {
        return new PostalRegionRegisterCommand(TEST_POSTAL_CODE, regionGroup, extraDeliveryDays);
    }

    private static PostalRegionUpdateCommand updateCommand(String regionGroup, int extraDeliveryDays) {
        return new PostalRegionUpdateCommand(TEST_POSTAL_CODE, regionGroup, extraDeliveryDays);
    }

    // 이력을 쌓인 순서대로 "동작 그룹 소요일 관리자ID"로 읽는다
    private List<String> histories() {
        return jdbcTemplate.queryForList(
                "SELECT CONCAT(action, ' ', region_group, ' ', extra_delivery_days, ' ', changed_by) "
                        + "FROM remote_area_postal_code_history WHERE postal_code = ? ORDER BY id",
                String.class, TEST_POSTAL_CODE);
    }
}
