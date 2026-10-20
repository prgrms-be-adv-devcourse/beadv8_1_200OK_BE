package com.ok.order.cart.repository;

import com.ok.order.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    boolean existsByCartIdAndProductOptionId(Long cartId, Long productOptionId);
    List<CartItem> findAllByCartId(Long cartId);
    Optional<CartItem> findByIdAndCartId(Long id, Long cartId);
    void deleteAllByCartIdAndSelectedTrue(Long cartId);
    List<CartItem> findAllByCartIdAndSelectedTrue(Long cartId);
    void deleteAllByCartIdAndProductOptionIdIn(Long cartId, List<Long> productOptionIds);
}
