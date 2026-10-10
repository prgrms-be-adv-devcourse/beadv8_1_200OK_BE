package com.ok.order.domain;

import com.ok.common.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cart")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseIdAndTime {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static Cart create(Long memberId) {
        Cart cart = new Cart();
        cart.memberId = memberId;
        return cart;
    }
}
