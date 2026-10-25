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

    /**
     * 도서산간 지역인지 판단한다.
     * 산간({@code MOUNTAIN})처럼 추가 운임이 0원이어도 도착 예정일이 늘어나므로, 운임이 아니라 지역 그룹으로 판단한다.
     */
    public boolean isRemoteArea() {
        return !RegionGroup.GENERAL_CODE.equals(regionGroup);
    }
}
