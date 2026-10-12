package com.ok.order.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseEntity;
import com.ok.order.exception.OrderErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "market_member")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketMember extends BaseEntity {
    @Id
    private Long id;

    @Column(nullable = false)
    private boolean purchasable;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static MarketMember create(Long memberId) {
        MarketMember member = new MarketMember();
        member.id = memberId;
        member.purchasable = true;
        return member;
    }

    public void validatePurchasable() {
        if (!purchasable) {
            throw new RestApiException(OrderErrorCode.MEMBER_CANNOT_PURCHASE);
        }
    }
}
