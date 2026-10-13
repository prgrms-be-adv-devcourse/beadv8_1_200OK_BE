package com.ok.order.cart.repository;

import com.ok.order.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    boolean existsByCartIdAndProductOptionId(Long cartId, Long productOptionId);
    List<CartItem> findAllByCartId(Long cartId);
}
