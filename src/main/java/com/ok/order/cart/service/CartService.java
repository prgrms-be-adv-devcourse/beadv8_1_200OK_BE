package com.ok.order.cart.service;

import com.ok.order.cart.domain.Cart;
import com.ok.order.cart.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {
    private final CartRepository cartRepository;

    @Transactional
    public void createCart(Long memberId) {
        if (cartRepository.existsByMemberId(memberId)) {
            return;
        }
        cartRepository.save(Cart.create(memberId));
    }
}
