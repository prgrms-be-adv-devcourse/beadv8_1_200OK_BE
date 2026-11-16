package com.ok.delivery.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.delivery.DeliverySchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 판매자 출고지 우편번호. 원본은 member이고, 배송은 Kafka로 받은 값을 복제해 둔다.
 * 판매자 배송 설정({@link SellerShippingProfile})과 원본 소유자가 달라 테이블을 나눈다.
 *
 * <p>같은 판매자의 메시지가 순서가 뒤바뀌어 오거나 다시 와도 최신 값이 남도록,
 * 반영한 메시지의 발생 시각을 함께 저장하고 그보다 오래된 메시지는 버린다.
 */
@Entity
@Getter
@Table(name = "seller_origin_postal_code", catalog = DeliverySchema.NAME)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerOriginPostalCode extends BaseTime {

    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    // null이면 출고지가 없는 판매자
    @Column(name = "postal_code", length = 5)
    private String postalCode;

    @Column(name = "source_occurred_at", nullable = false)
    private LocalDateTime sourceOccurredAt;

    private SellerOriginPostalCode(Long sellerId, String postalCode, LocalDateTime sourceOccurredAt) {
        if (sellerId == null) {
            throw new IllegalArgumentException("판매자 ID는 필수입니다.");
        }
        if (sourceOccurredAt == null) {
            throw new IllegalArgumentException("메시지 발생 시각은 필수입니다.");
        }
        this.sellerId = sellerId;
        this.postalCode = postalCode;
        this.sourceOccurredAt = sourceOccurredAt;
    }

    public static SellerOriginPostalCode create(Long sellerId, String postalCode, LocalDateTime occurredAt) {
        return new SellerOriginPostalCode(sellerId, postalCode, occurredAt);
    }

    /**
     * 메시지의 값으로 덮어쓴다. 저장된 값보다 오래된 메시지면 바꾸지 않는다.
     * 같은 시각의 메시지는 같은 값이 다시 온 것으로 보고 반영한다.
     *
     * @return 값을 반영했으면 true, 오래된 메시지라 버렸으면 false
     * @throws IllegalArgumentException 발생 시각이 없으면
     */
    public boolean update(String postalCode, LocalDateTime occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("메시지 발생 시각은 필수입니다.");
        }
        if (occurredAt.isBefore(sourceOccurredAt)) {
            return false;
        }
        this.postalCode = postalCode;
        this.sourceOccurredAt = occurredAt;
        return true;
    }
}
