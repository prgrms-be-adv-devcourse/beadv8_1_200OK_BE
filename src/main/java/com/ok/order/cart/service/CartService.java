package com.ok.order.cart.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.controller.dto.CartResponse;
import com.ok.order.cart.domain.Cart;
import com.ok.order.cart.domain.CartItem;
import com.ok.order.cart.exception.CartErrorCode;
import com.ok.order.cart.repository.CartItemRepository;
import com.ok.order.cart.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    // 장바구니 생성
    @Transactional
    public void createCart(Long memberId) {
        if (cartRepository.existsByMemberId(memberId)) {
            return;
        }
        cartRepository.save(Cart.create(memberId));
    }

    // 장바구니에 상품 추가
    @Transactional
    public Long addItem(Long memberId, Long productOptionId, int quantity) {
        // TODO 회원 모듈 구매 가능 여부 API 제공 후 연동 → MEMBER_CANNOT_PURCHASE


        // TODO 상품 모듈 옵션 조회 API 연동 후: 판매 중인지, 본인 상품인지 검증


        Cart cart = getCart(memberId);

        if (cartItemRepository.existsByCartIdAndProductOptionId(cart.getId(), productOptionId)) {
            throw new RestApiException(CartErrorCode.CART_ITEM_ALREADY_EXISTS);
        }

        CartItem cartItem = CartItem.create(cart, productOptionId, quantity);
        return cartItemRepository.save(cartItem).getId();
    }

    // 장바구니 리스트 조회
    public CartResponse getMyCart(Long memberId) {
        Cart cart = getCart(memberId);

        List<CartItem> items = cartItemRepository.findAllByCartId(cart.getId());

        return CartResponse.of(cart, items);
    }

    // 장바구니 조회
    private Cart getCart(Long memberId) {
        return cartRepository.findByMemberId(memberId)
                .orElseThrow(() -> new RestApiException(CartErrorCode.CART_NOT_FOUND));
    }
}
