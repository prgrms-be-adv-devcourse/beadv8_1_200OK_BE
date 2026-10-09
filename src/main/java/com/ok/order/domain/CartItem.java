package com.ok.order.domain;

import com.ok.common.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cart_item")
@Getter
@NoArgsConstructor
public class CartItem extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean selected;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
