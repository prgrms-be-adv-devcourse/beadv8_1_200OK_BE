package com.ok.order.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseIdAndTime;
import com.ok.order.exception.OrderErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cart_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    public static CartItem create(Cart cart, Long productId, Long productOptionId, int quantity) {
        if (quantity < 1) {
            throw new RestApiException(OrderErrorCode.INVALID_QUANTITY);
        }

        CartItem item = new CartItem();
        item.cart = cart;
        item.productId = productId;
        item.productOptionId = productOptionId;
        item.quantity = quantity;
        item.selected = true;
        return item;
    }
}
