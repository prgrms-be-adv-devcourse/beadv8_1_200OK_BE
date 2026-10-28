package com.ok.delivery.repository;

import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RemoteAreaPostalCodeRepositoryIntegrationTest extends AbstractIntegrationTest {

    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99999";

    @Autowired
    RemoteAreaPostalCodeRepository postalCodeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM remote_area_postal_code WHERE postal_code = ?", TEST_POSTAL_CODE);
    }

    @Test
    void 도서산간_우편번호를_저장하고_조회한다() {
        // given
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "JEJU_ISLANDS", 2));

        // when
        RemoteAreaPostalCode sut = postalCodeRepository.findById(TEST_POSTAL_CODE).orElseThrow();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPostalCode()).isEqualTo(TEST_POSTAL_CODE);
            softly.assertThat(sut.getRegionGroup()).isEqualTo("JEJU_ISLANDS");
            softly.assertThat(sut.getExtraDeliveryDays()).isEqualTo(2);
            softly.assertThat(sut.getCreatedAt()).isNotNull();
            softly.assertThat(sut.isNew()).isFalse();
            softly.assertThat(sut.getDeletedAt()).isNull();
        });
    }

    @Test
    void 같은_우편번호를_다시_저장하면_덮어쓰지_않고_중복_오류가_난다() {
        // given
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "JEJU_ISLANDS", 2));

        // when & then
        assertThatThrownBy(() -> postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "ULLEUNG", 3)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(postalCodeRepository.findById(TEST_POSTAL_CODE).orElseThrow().getRegionGroup()).isEqualTo("JEJU_ISLANDS");
    }

    @Test
    void 없는_지역_그룹은_외래키로_막는다() {
        // when & then
        assertThatThrownBy(() -> postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "NOT_EXISTS", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 일반_지역은_도서산간_우편번호로_등록할_수_없다() {
        // when & then
        assertThatThrownBy(() -> postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "GENERAL", 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // CHECK 위반은 MySQL 오류 3819라 Spring이 DataIntegrityViolationException으로 분류하지 않는다
    @Test
    void 추가_소요일이_음수인_행은_체크_제약으로_막는다() {
        // when & then
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO remote_area_postal_code (postal_code, region_group, extra_delivery_days, created_at, updated_at) "
                        + "VALUES (?, 'JEJU_MAIN', -1, NOW(6), NOW(6))", TEST_POSTAL_CODE))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_remote_area_postal_code_extra_delivery_days");
    }

    @Test
    void 우편번호가_숫자_5자리가_아닌_행은_체크_제약으로_막는다() {
        // when & then
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO remote_area_postal_code (postal_code, region_group, extra_delivery_days, created_at, updated_at) "
                        + "VALUES ('6300a', 'JEJU_MAIN', 1, NOW(6), NOW(6))"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_remote_area_postal_code_postal_code");
    }

    @Test
    void 삭제되지_않은_우편번호는_활성_조회로_찾는다() {
        // given
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "JEJU_ISLANDS", 2));

        // when
        Optional<RemoteAreaPostalCode> sut = postalCodeRepository.findByPostalCodeAndDeletedAtIsNull(TEST_POSTAL_CODE);

        // then
        assertThat(sut).isPresent();
    }

    @Test
    void 삭제된_우편번호는_활성_조회로_찾지_못하지만_기본키로는_조회된다() {
        // given
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "JEJU_ISLANDS", 2));
        jdbcTemplate.update("UPDATE remote_area_postal_code SET deleted_at = NOW(6) WHERE postal_code = ?", TEST_POSTAL_CODE);

        // when
        Optional<RemoteAreaPostalCode> active = postalCodeRepository.findByPostalCodeAndDeletedAtIsNull(TEST_POSTAL_CODE);
        Optional<RemoteAreaPostalCode> any = postalCodeRepository.findById(TEST_POSTAL_CODE);

        // then
        assertThat(active).isEmpty();
        // 삭제된 번호를 다시 등록할 때 되살릴 수 있도록 기본키 조회에는 삭제된 행도 나와야 한다
        assertThat(any).isPresent();
        assertThat(any.get().getDeletedAt()).isNotNull();
    }
}
