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

    // 담을 수 있는 상품 옵션인지 검증
    private void validateAddable(Long memberId, Long productOptionId) {
        // TODO: 상품 모듈의 API를 사용하여 검증 로직을 구현
    }

    // 장바구니 상품 수량 변경
    @Transactional
    public void changeQuantity(Long memberId, Long cartItemId, int quantity) {
        CartItem item = getMyCartItem(memberId, cartItemId);
        item.changeQuantity(quantity);
    }

    // 장바구니 상품 선택·해제
    @Transactional
    public void changeSelected(Long memberId, Long cartItemId, boolean selected) {
        CartItem item = getMyCartItem(memberId, cartItemId);
        item.changeSelected(selected);
    }

    // 장바구니 상품 전체 선택·해제
    @Transactional
    public void changeAllSelected(Long memberId, boolean selected) {
        Cart cart = getCart(memberId);
        cartItemRepository.findAllByCartId(cart.getId())
                .forEach(item -> item.changeSelected(selected));
    }

    // 장바구니 상품 삭제
    @Transactional
    public void deleteItem(Long memberId, Long cartItemId) {
        CartItem item = getMyCartItem(memberId, cartItemId);
        cartItemRepository.delete(item);
    }

    // 선택한 장바구니 상품 삭제
    @Transactional
    public void deleteSelectedItems(Long memberId) {
        Cart cart = getCart(memberId);
        cartItemRepository.deleteAllByCartIdAndSelectedTrue(cart.getId());
    }

    // 내 장바구니 조회
    private Cart getCart(Long memberId) {
        return cartRepository.findByMemberId(memberId)
                .orElseThrow(() -> new RestApiException(CartErrorCode.CART_NOT_FOUND));
    }

    // 내 장바구니 안의 상품 조회
    private CartItem getMyCartItem(Long memberId, Long cartItemId) {
        Cart cart = getCart(memberId);
        return cartItemRepository.findByIdAndCartId(cartItemId, cart.getId())
                .orElseThrow(() -> new RestApiException(CartErrorCode.CART_ITEM_NOT_FOUND));
    }
}
