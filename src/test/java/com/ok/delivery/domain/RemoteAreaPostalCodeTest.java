package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RemoteAreaPostalCodeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 10, 0);

    @Test
    void 정상값으로_도서산간_우편번호를_생성한다() {
        // given
        String postalCode = "63000";
        String regionGroup = "JEJU_ISLANDS";
        int extraDeliveryDays = 2;

        // when
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of(postalCode, regionGroup, extraDeliveryDays);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPostalCode()).isEqualTo(postalCode);
            softly.assertThat(sut.getId()).isEqualTo(postalCode);
            softly.assertThat(sut.getRegionGroup()).isEqualTo(regionGroup);
            softly.assertThat(sut.getExtraDeliveryDays()).isEqualTo(extraDeliveryDays);
            softly.assertThat(sut.getDeletedAt()).isNull();
        });
    }

    @Test
    void 추가_소요일_0은_허용한다() {
        // when
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 0);

        // then
        assertThat(sut.getExtraDeliveryDays()).isZero();
    }

    @Test
    void 저장하기_전에는_새_엔티티다() {
        // when
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);

        // then
        assertThat(sut.isNew()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("잘못된_우편번호")
    void 우편번호가_숫자_5자리가_아니면_거부한다(String postalCode) {
        // when & then
        assertThatThrownBy(() -> RemoteAreaPostalCode.of(postalCode, "JEJU_MAIN", 1))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_POSTAL_CODE));
    }

    static Stream<String> 잘못된_우편번호() {
        return Stream.of("6300", "630000", "6300a", "63-00", " 6300", "", " ", null);
    }

    @ParameterizedTest
    @MethodSource("잘못된_지역_그룹")
    void 지역_그룹이_비어_있으면_거부한다(String regionGroup) {
        // when & then
        assertThatThrownBy(() -> RemoteAreaPostalCode.of("63000", regionGroup, 1))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.MISSING_REGION_GROUP));
    }

    static Stream<String> 잘못된_지역_그룹() {
        return Stream.of("", " ", null);
    }

    @Test
    void 추가_소요일이_음수면_거부한다() {
        // when & then
        assertThatThrownBy(() -> RemoteAreaPostalCode.of("63000", "JEJU_MAIN", -1))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_EXTRA_DELIVERY_DAYS));
    }

    @Test
    void 삭제하면_삭제_시각이_기록된다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);

        // when
        sut.delete(NOW);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.isDeleted()).isTrue();
            softly.assertThat(sut.getDeletedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 이미_삭제된_우편번호는_다시_삭제할_수_없다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);
        sut.delete(NOW);

        // when & then
        assertThatThrownBy(() -> sut.delete(NOW.plusMinutes(1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
        assertThat(sut.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void 삭제된_우편번호를_다시_등록하면_새_값으로_되살아난다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);
        sut.delete(NOW);

        // when
        sut.reregister("JEJU_ISLANDS", 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.isDeleted()).isFalse();
            softly.assertThat(sut.getDeletedAt()).isNull();
            softly.assertThat(sut.getRegionGroup()).isEqualTo("JEJU_ISLANDS");
            softly.assertThat(sut.getExtraDeliveryDays()).isEqualTo(2);
        });
    }

    @Test
    void 삭제되지_않은_우편번호는_다시_등록할_수_없다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);

        // when & then
        assertThatThrownBy(() -> sut.reregister("JEJU_ISLANDS", 2))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.POSTAL_REGION_DUPLICATED));
        assertThat(sut.getRegionGroup()).isEqualTo("JEJU_MAIN");
    }

    @Test
    void 수정하면_새_그룹과_소요일로_바뀌고_바뀌었다고_알린다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);

        // when
        boolean changed = sut.update("JEJU_ISLANDS", 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getRegionGroup()).isEqualTo("JEJU_ISLANDS");
            softly.assertThat(sut.getExtraDeliveryDays()).isEqualTo(2);
        });
    }

    @Test
    void 같은_값으로_수정하면_바뀌지_않았다고_알린다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);

        // when
        boolean changed = sut.update("JEJU_MAIN", 1);

        // then
        assertThat(changed).isFalse();
    }

    @Test
    void 삭제된_우편번호는_수정할_수_없다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);
        sut.delete(NOW);

        // when & then
        assertThatThrownBy(() -> sut.update("JEJU_ISLANDS", 2))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
        assertSoftly(softly -> {
            softly.assertThat(sut.isDeleted()).isTrue();
            softly.assertThat(sut.getRegionGroup()).isEqualTo("JEJU_MAIN");
        });
    }

    @ParameterizedTest
    @MethodSource("잘못된_지역_그룹")
    void 수정할_때_지역_그룹이_비어_있으면_거부한다(String regionGroup) {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);

        // when & then
        assertThatThrownBy(() -> sut.update(regionGroup, 2))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.MISSING_REGION_GROUP));
        assertThat(sut.getRegionGroup()).isEqualTo("JEJU_MAIN");
    }

    @Test
    void 수정할_때_추가_소요일이_음수면_거부한다() {
        // given
        RemoteAreaPostalCode sut = RemoteAreaPostalCode.of("63000", "JEJU_MAIN", 1);

        // when & then
        assertThatThrownBy(() -> sut.update("JEJU_ISLANDS", -1))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_EXTRA_DELIVERY_DAYS));
        assertThat(sut.getExtraDeliveryDays()).isEqualTo(1);
    }
}
