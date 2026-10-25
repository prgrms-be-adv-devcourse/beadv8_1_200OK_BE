package com.ok.delivery.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 도서산간 지역 그룹과 그룹별 추가 운임. 마이그레이션으로만 바뀌는 기준 데이터라 수정 메서드가 없다.
 *
 * <p>일반 지역({@link #GENERAL_CODE})은 행을 두지 않는다. 우편번호 테이블이 이 테이블을 외래키로 참조하므로,
 * 일반 지역을 도서산간 우편번호로 등록하는 일이 DB 제약으로 막힌다.
 *
 * <p>그룹별 범위는 다음과 같다.
 * <ul>
 *   <li>{@code JEJU_MAIN}: 제주특별자치도 본섬</li>
 *   <li>{@code JEJU_ISLANDS}: 제주 부속 도서(추자도, 우도, 가파도, 마라도)</li>
 *   <li>{@code ULLEUNG}: 울릉군</li>
 *   <li>{@code WEST_SEA_ISLANDS}: 옹진군 백령면·대청면·연평면(서해 5도)</li>
 *   <li>{@code ETC_ISLANDS}: 위에 해당하지 않는 나머지 섬(전남·경남·인천·경기·충남·전북 등)</li>
 *   <li>{@code MOUNTAIN}: 강원·경북 산간. 추가 운임 없이 도착 예정일만 늘린다.
 *       근거 자료가 없어 우편번호는 비어 있고, 근거가 생기면 관리자 API로 등록한다</li>
 * </ul>
 */
@Entity
@Getter
@Table(name = "region_group")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegionGroup extends BaseTime {

    /** 도서산간이 아닌 지역. {@code shipment.region_group}에 저장하는 값과 같다 */
    public static final String GENERAL_CODE = "GENERAL";

    @Id
    @Column(name = "code", length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "surcharge", nullable = false)
    private int surcharge;

    @Builder
    private RegionGroup(String code, String name, int surcharge) {
        this.code = code;
        this.name = name;
        this.surcharge = surcharge;
    }
}
