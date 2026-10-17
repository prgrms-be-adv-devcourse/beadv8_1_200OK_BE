package com.ok.delivery.service.dto;

import com.ok.delivery.domain.RegionGroup;

/**
 * 우편번호로 판정한 지역.
 *
 * @param regionGroup       지역 그룹 코드. 도서산간이 아니면 {@code GENERAL}
 * @param surcharge         추가 운임(원)
 * @param extraDeliveryDays 추가 소요일(영업일)
 */
public record RegionResult(String regionGroup, int surcharge, int extraDeliveryDays) {

    public static RegionResult general() {
        return new RegionResult(RegionGroup.GENERAL_CODE, 0, 0);
    }
}
