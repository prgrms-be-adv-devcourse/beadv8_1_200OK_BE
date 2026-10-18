package com.ok.delivery.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 도서산간 우편번호의 등록·삭제·되살림 기록. 추가만 하고 수정·삭제하지 않는다.
 * 그룹과 소요일은 변경 직후(삭제는 삭제 시점) 값이다. 변경 시각은 {@code createdAt}이다.
 */
@Entity
@Immutable
@Getter
@Table(name = "remote_area_postal_code_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RemoteAreaPostalCodeHistory extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "postal_code", nullable = false, length = 5)
    private String postalCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private PostalCodeHistoryAction action;

    @Column(name = "region_group", nullable = false, length = 30)
    private String regionGroup;

    @Column(name = "extra_delivery_days", nullable = false)
    private int extraDeliveryDays;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    // 세 값 모두 사용자 입력이 아니라 서비스가 넘기는 값이라(관리자 ID는 토큰에서 꺼냄), 비어 있으면 호출한 코드의 버그다
    private RemoteAreaPostalCodeHistory(PostalCodeHistoryAction action, RemoteAreaPostalCode postalCode, Long changedBy) {
        if (action == null) {
            throw new IllegalArgumentException("이력 동작은 필수입니다.");
        }
        if (postalCode == null) {
            throw new IllegalArgumentException("우편번호는 필수입니다.");
        }
        if (changedBy == null) {
            throw new IllegalArgumentException("변경한 관리자 ID는 필수입니다.");
        }
        this.postalCode = postalCode.getPostalCode();
        this.action = action;
        this.regionGroup = postalCode.getRegionGroup();
        this.extraDeliveryDays = postalCode.getExtraDeliveryDays();
        this.changedBy = changedBy;
    }

    /**
     * 우편번호를 바꾼 직후의 상태로 이력을 만든다. 우편번호를 바꾼 다음에 호출해야 변경 직후 값이 남는다.
     *
     * @param changedBy 변경한 관리자의 회원 ID
     * @throws IllegalArgumentException 동작, 우편번호, 관리자 ID 중 하나라도 {@code null}이면
     */
    public static RemoteAreaPostalCodeHistory of(PostalCodeHistoryAction action, RemoteAreaPostalCode postalCode, Long changedBy) {
        return new RemoteAreaPostalCodeHistory(action, postalCode, changedBy);
    }
}
