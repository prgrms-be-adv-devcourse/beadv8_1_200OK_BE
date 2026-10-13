package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

@Entity
@Immutable
@Table(name = "payout_candidate_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutCandidateItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutEventType eventType;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long amount;

    private static PayoutCandidateItem create(PayoutEventType eventType, Long orderItemId,
                                             Long sellerId, Long amount) {
        PayoutCandidateItem item = new PayoutCandidateItem();
        item.eventType = eventType;
        item.orderItemId = orderItemId;
        item.sellerId = sellerId;
        item.amount = amount;
        return item;
    }

    public static PayoutCandidateItem ofSaleFee(Long orderItemId, Long sellerId, Long amount) {
        return create(PayoutEventType.SALE_FEE, orderItemId, sellerId, amount);
    }

    public static PayoutCandidateItem ofSaleAmount(Long orderItemId, Long sellerId, Long amount) {
        return create(PayoutEventType.SALE_AMOUNT, orderItemId, sellerId, amount);
    }

    public static PayoutCandidateItem ofSaleShippingFee(Long orderItemId, Long sellerId, Long amount) {
        return create(PayoutEventType.SALE_SHIPPING_FEE, orderItemId, sellerId, amount);
    }

    // 시스템(플랫폼) 몫 여부: SALE_FEE는 시스템, 나머지는 판매자. 이벤트 타입이 추가되면 함께 수정
    public boolean isSystemShare() {
        return eventType == PayoutEventType.SALE_FEE;
    }
}
