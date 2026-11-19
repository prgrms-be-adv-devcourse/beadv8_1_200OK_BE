package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.PostalRegionRegisterCommand;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.port.out.RegionGroupRepository;
import com.ok.delivery.application.port.out.RemoteAreaPostalCodeHistoryRepository;
import com.ok.delivery.application.port.out.RemoteAreaPostalCodeRepository;
import com.ok.delivery.application.port.out.SellerOriginPostalCodeRepository;
import com.ok.delivery.domain.RegionGroup;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.domain.SellerOriginPostalCode;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class DeliveryRegionServiceTest {

    @Mock
    RemoteAreaPostalCodeRepository postalCodeRepository;

    @Mock
    RegionGroupRepository regionGroupRepository;

    @Mock
    RemoteAreaPostalCodeHistoryRepository historyRepository;

    @Mock
    SellerOriginPostalCodeRepository sellerOriginPostalCodeRepository;

    @InjectMocks
    DeliveryRegionService deliveryRegionService;

    @Test
    void 등록되지_않은_우편번호는_일반_지역으로_판정한다() {
        // given
        given(postalCodeRepository.findActive("06236")).willReturn(Optional.empty());

        // when
        RegionResult sut = deliveryRegionService.resolve("06236");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.regionGroup()).isEqualTo("GENERAL");
            softly.assertThat(sut.surcharge()).isZero();
            softly.assertThat(sut.extraDeliveryDays()).isZero();
        });
    }

    @Test
    void 등록된_우편번호는_그룹의_추가_운임과_우편번호의_추가_소요일로_판정한다() {
        // given
        given(postalCodeRepository.findActive("40200")).willReturn(Optional.of(RemoteAreaPostalCode.of("40200", "ULLEUNG", 2)));
        given(regionGroupRepository.findById("ULLEUNG")).willReturn(Optional.of(
                RegionGroup.builder().code("ULLEUNG").name("울릉도").surcharge(5_000).build()));

        // when
        RegionResult sut = deliveryRegionService.resolve("40200");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.regionGroup()).isEqualTo("ULLEUNG");
            softly.assertThat(sut.surcharge()).isEqualTo(5_000);
            softly.assertThat(sut.extraDeliveryDays()).isEqualTo(2);
        });
    }

    @Test
    void 지역_그룹_행이_없으면_일반_지역으로_숨기지_않고_실패한다() {
        // given
        given(postalCodeRepository.findActive("40200")).willReturn(Optional.of(RemoteAreaPostalCode.of("40200", "ULLEUNG", 2)));
        given(regionGroupRepository.findById("ULLEUNG")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> deliveryRegionService.resolve("40200"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 우편번호가_null이면_일반_지역으로_숨기지_않고_실패한다() {
        // when & then
        assertThatThrownBy(() -> deliveryRegionService.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("우편번호는 필수입니다.");
    }

    @Test
    void 배송지가_제주_본섬이면_출고지도_제주_본섬인_판매자만_추가_운임을_받지_않는다() {
        // given: 10 제주 본섬 출고, 20 서울 출고, 30 출고지 사본 없음, 40 출고지 우편번호 없음
        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 19, 12, 0);
        List<Long> sellerIds = List.of(10L, 20L, 30L, 40L);
        given(sellerOriginPostalCodeRepository.findAllBySellerIdIn(sellerIds)).willReturn(List.of(
                SellerOriginPostalCode.create(10L, "63200", occurredAt),
                SellerOriginPostalCode.create(20L, "06236", occurredAt),
                SellerOriginPostalCode.create(40L, null, occurredAt)));
        given(postalCodeRepository.findActiveIn(Set.of("63200", "06236"), "JEJU_MAIN"))
                .willReturn(List.of(RemoteAreaPostalCode.of("63200", "JEJU_MAIN", 1)));

        // when
        Map<Long, Integer> sut = deliveryRegionService.resolveSurcharges(new RegionResult("JEJU_MAIN", 3_000, 1), sellerIds);

        // then
        assertThat(sut).containsExactly(
                Map.entry(10L, 0), Map.entry(20L, 3_000), Map.entry(30L, 3_000), Map.entry(40L, 3_000));
    }

    @ParameterizedTest
    @MethodSource("제주_본섬이_아닌_배송지")
    void 배송지가_제주_본섬이_아니면_출고지를_조회하지_않고_배송지_운임을_받는다(RegionResult destination) {
        // when
        Map<Long, Integer> sut = deliveryRegionService.resolveSurcharges(destination, List.of(10L, 20L));

        // then
        assertThat(sut).containsExactly(
                Map.entry(10L, destination.surcharge()), Map.entry(20L, destination.surcharge()));
        then(sellerOriginPostalCodeRepository).shouldHaveNoInteractions();
    }

    // 부속 도서는 본섬과 그룹이 달라, 제주 본섬에서 보내도 면제하지 않는다
    static Stream<RegionResult> 제주_본섬이_아닌_배송지() {
        return Stream.of(
                RegionResult.general(),
                new RegionResult("JEJU_ISLANDS", 5_000, 2),
                new RegionResult("ULLEUNG", 5_000, 2));
    }

    @Test
    void 판매자_한_명의_추가_운임도_같은_규칙으로_판정한다() {
        // given
        given(sellerOriginPostalCodeRepository.findAllBySellerIdIn(List.of(10L))).willReturn(List.of(
                SellerOriginPostalCode.create(10L, "63200", LocalDateTime.of(2026, 11, 19, 12, 0))));
        given(postalCodeRepository.findActiveIn(Set.of("63200"), "JEJU_MAIN"))
                .willReturn(List.of(RemoteAreaPostalCode.of("63200", "JEJU_MAIN", 1)));

        // when
        int sut = deliveryRegionService.resolveSurcharge(new RegionResult("JEJU_MAIN", 3_000, 1), 10L);

        // then
        assertThat(sut).isZero();
    }

    @Test
    void 배송지_지역이나_판매자_ID가_없으면_운임을_숨기지_않고_실패한다() {
        // when & then
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> deliveryRegionService.resolveSurcharges(null, List.of(10L)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> deliveryRegionService.resolveSurcharges(RegionResult.general(), null))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> deliveryRegionService.resolveSurcharge(RegionResult.general(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @ParameterizedTest
    @MethodSource("동시_등록에서_나는_DB_예외")
    void 같은_번호를_동시에_처음_등록해_DB에서_실패하면_중복으로_응답한다(RuntimeException dbException) {
        // given
        given(postalCodeRepository.findByIdForUpdate("40200")).willReturn(Optional.empty());
        given(regionGroupRepository.existsById("ULLEUNG")).willReturn(true);
        given(postalCodeRepository.saveAndFlush(any(RemoteAreaPostalCode.class))).willThrow(dbException);

        // when & then
        assertThatThrownBy(() -> deliveryRegionService.register(new PostalRegionRegisterCommand("40200", "ULLEUNG", 2), 1L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.POSTAL_REGION_DUPLICATED));
    }

    // PK 중복, 그리고 없는 행을 FOR UPDATE로 읽어 잡힌 gap 잠금끼리 INSERT에서 생기는 교착 상태
    static Stream<RuntimeException> 동시_등록에서_나는_DB_예외() {
        return Stream.of(
                new DataIntegrityViolationException("Duplicate entry"),
                new CannotAcquireLockException("Deadlock found"));
    }
}
