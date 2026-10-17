package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.delivery.exception.DeliveryErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 도서산간 우편번호와 그 지역의 그룹, 추가 소요일. 여기에 없거나 삭제된 우편번호는 일반 지역이다.
 *
 * <p>삭제는 soft delete다. PK가 우편번호라서 삭제한 번호를 다시 등록하면 새 행이 아니라 이 행을 되살린다.
 *
 * <p>우편번호를 PK로 직접 할당하므로 {@link Persistable}을 구현한다. 구현하지 않으면 {@code save()}가 이미 있는 것으로 보고
 * {@code merge}를 해서, 같은 우편번호를 다시 저장해도 중복 오류 없이 덮어쓴다.
 */
@Entity
@Getter
@Table(name = "remote_area_postal_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RemoteAreaPostalCode extends BaseTime implements Persistable<String> {

    private static final Pattern POSTAL_CODE = Pattern.compile("^\\d{5}$");

    @Id
    @Column(name = "postal_code", length = 5)
    private String postalCode;

    // 연관 매핑 없이 코드만 둔다. 존재 여부는 DB 외래키가 지킨다
    @Column(name = "region_group", nullable = false, length = 30)
    private String regionGroup;

    @Column(name = "extra_delivery_days", nullable = false)
    private int extraDeliveryDays;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private RemoteAreaPostalCode(String postalCode, String regionGroup, int extraDeliveryDays) {
        if (postalCode == null || !POSTAL_CODE.matcher(postalCode).matches()) {
            throw new RestApiException(DeliveryErrorCode.INVALID_POSTAL_CODE);
        }
        if (regionGroup == null || regionGroup.isBlank()) {
            throw new RestApiException(DeliveryErrorCode.MISSING_REGION_GROUP);
        }
        if (extraDeliveryDays < 0) {
            throw new RestApiException(DeliveryErrorCode.INVALID_EXTRA_DELIVERY_DAYS);
        }
        this.postalCode = postalCode;
        this.regionGroup = regionGroup;
        this.extraDeliveryDays = extraDeliveryDays;
    }

    /**
     * 도서산간 우편번호를 만든다.
     * 지역 그룹이 실제로 있는 값인지는 DB 외래키가 확인한다.
     *
     * @throws RestApiException 우편번호가 숫자 5자리가 아니면 {@code INVALID_POSTAL_CODE},
     *                          지역 그룹이 비어 있으면 {@code MISSING_REGION_GROUP},
     *                          추가 소요일이 음수면 {@code INVALID_EXTRA_DELIVERY_DAYS}
     */
    public static RemoteAreaPostalCode of(String postalCode, String regionGroup, int extraDeliveryDays) {
        return new RemoteAreaPostalCode(postalCode, regionGroup, extraDeliveryDays);
    }

    @Override
    public String getId() {
        return postalCode;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
