package com.ok.order.cart.domain;

import com.ok.order.OrderSchema;
import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.order.cart.exception.CartErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cart_item", catalog = OrderSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseTime {

    // 한 장바구니 상품에 담을 수 있는 최대 수량. 다시 담기 합산도 이 값에서 멈춘다
    // 프론트(MAX_CART_QUANTITY)도 같은 값을 쓰므로 바꿀 때 함께 맞춘다
    public static final int MAX_QUANTITY = 99;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean selected;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static CartItem create(Cart cart, Long productOptionId, int quantity) {
        validateQuantity(quantity);

        CartItem item = new CartItem();
        item.cart = cart;
        item.productOptionId = productOptionId;
        item.quantity = quantity;
        item.selected = true;
        return item;
    }

    public void changeQuantity(int quantity) {
        validateQuantity(quantity);
        this.quantity = quantity;
    }

    public void changeSelected(boolean selected) {
        this.selected = selected;
    }

    // 담기는 네이티브 쿼리(CartItemRepository.insertOrAddQuantity)로 저장해 create를 거치지 않으므로, 서비스가 저장 전에 부를 수 있게 공개한다
    public static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new RestApiException(CartErrorCode.INVALID_QUANTITY);
        }
    }
}
