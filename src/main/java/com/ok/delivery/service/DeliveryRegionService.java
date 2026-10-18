package com.ok.delivery.service;

import com.ok.delivery.domain.RegionGroup;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.repository.RegionGroupRepository;
import com.ok.delivery.repository.RemoteAreaPostalCodeRepository;
import com.ok.delivery.service.dto.RegionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 우편번호로 지역 그룹, 추가 운임, 추가 소요일을 판정한다.
 * 견적, 배송 생성, 배송지 변경이 같은 판정을 쓰도록 한 곳에 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryRegionService {

    private final RemoteAreaPostalCodeRepository postalCodeRepository;
    private final RegionGroupRepository regionGroupRepository;

    /**
     * 등록되지 않았거나 삭제된 우편번호는 일반 지역({@code GENERAL}, 0원, 0일)으로 판정한다.
     * 입력은 {@code Address}나 요청 DTO에서 이미 검증된 우편번호다. 형식은 다시 검사하지 않으며, 형식이 틀린 값은 등록되지 않은 번호로 본다.
     * {@code null}은 호출한 코드의 버그이고, 일반 지역(추가 운임 0원)으로 숨기면 결제 금액이 틀려지므로 예외로 드러낸다.
     *
     * @throws IllegalArgumentException 우편번호가 {@code null}이면
     */
    public RegionResult resolve(String postalCode) {
        if (postalCode == null) {
            throw new IllegalArgumentException("우편번호는 필수입니다.");
        }
        return postalCodeRepository.findByPostalCodeAndDeletedAtIsNull(postalCode)
                .map(this::toResult)
                .orElseGet(RegionResult::general);
    }

    private RegionResult toResult(RemoteAreaPostalCode postalCode) {
        RegionGroup group = regionGroupRepository.findById(postalCode.getRegionGroup())
                .orElseThrow(() -> new IllegalStateException(
                        "지역 그룹이 없습니다. postalCode=" + postalCode.getPostalCode() + ", regionGroup=" + postalCode.getRegionGroup()));
        return new RegionResult(group.getCode(), group.getSurcharge(), postalCode.getExtraDeliveryDays());
    }
}
