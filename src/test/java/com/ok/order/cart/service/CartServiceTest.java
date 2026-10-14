package com.ok.order.cart.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.controller.dto.CartResponse;
import com.ok.order.cart.domain.Cart;
import com.ok.order.cart.domain.CartItem;
import com.ok.order.cart.exception.CartErrorCode;
import com.ok.order.cart.repository.CartItemRepository;
import com.ok.order.cart.repository.CartRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
public class CartServiceTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;

    @InjectMocks CartService cartService;

    static final Long BUYER_ID = 1L;
    static final Long SELLER_ID = 99L;

    // ---------- 장바구니 생성 ----------

    @Test
    void 장바구니가_없으면_생성한다() {
        // Given
        given(cartRepository.existsByMemberId(BUYER_ID)).willReturn(false);

        // When
        cartService.createCart(BUYER_ID);

        // Then
        then(cartRepository).should().save(any(Cart.class));
    }

    @Test
    void 장바구니가_이미_있으면_생성하지_않는다() {
        // Given
        given(cartRepository.existsByMemberId(BUYER_ID)).willReturn(true);

        // When
        cartService.createCart(BUYER_ID);

        // Then
        then(cartRepository).should(never()).save(any(Cart.class));
    }

    // ---------- 담기 ----------

    @Disabled("상품 모듈 옵션 조회 연동 후 작성")
    @Test
    void 판매_중인_상품의_옵션을_담을_수_있다() { }

    @Disabled("상품 모듈 옵션 조회 연동 후 작성")
    @Test
    void 없는_상품_옵션은_담을_수_없다() { }

    @Disabled("상품 모듈 옵션 조회 연동 후 작성")
    @Test
    void 판매_중이_아닌_상품은_담을_수_없다() { }

    @Disabled("상품 모듈 옵션 조회 연동 후 작성")
    @Test
    void 내가_판매하는_상품은_담을_수_없다() { }

    @Test
    void 이미_담긴_상품은_담을_수_없다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.existsByCartIdAndProductOptionId(cart.getId(), 100L)).willReturn(true);

        // When & Then
        assertThatThrownBy(() -> cartService.addItem(BUYER_ID, 100L, 1))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(CartErrorCode.CART_ITEM_ALREADY_EXISTS);
        then(cartItemRepository).should(never()).save(any(CartItem.class));
    }

    @Test
    void 장바구니가_없는_회원은_담을_수_없다() {
        // Given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> cartService.addItem(BUYER_ID, 100L, 1))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(CartErrorCode.CART_NOT_FOUND);
    }

    // ---------- 조회 ----------

    @Test
    void 장바구니를_조회하면_담긴_상품_목록을_확인할_수_있다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findAllByCartId(cart.getId())).willReturn(List.of(
                CartItem.create(cart, 100L, 2),
                CartItem.create(cart, 101L, 1)));

        // When
        CartResponse response = cartService.getMyCart(BUYER_ID);

        // Then
        assertThat(response.items()).hasSize(2);
        assertThat(response.items())
                .extracting("productOptionId")
                .containsExactly(100L, 101L);
    }

    @Test
    void 장바구니가_없는_회원은_조회할_수_없다() {
        // Given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> cartService.getMyCart(BUYER_ID))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(CartErrorCode.CART_NOT_FOUND);
    }

    // ---------- 수량 변경 · 선택 · 삭제 ----------

    @Test
    void 내_장바구니_상품의_수량을_변경할_수_있다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        CartItem item = CartItem.create(cart, 100L, 1);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findByIdAndCartId(1L, cart.getId())).willReturn(Optional.of(item));

        // When
        cartService.changeQuantity(BUYER_ID, 1L, 5);

        // Then
        assertThat(item.getQuantity()).isEqualTo(5);
    }

    @Test
    void 다른_사람의_장바구니_상품은_변경할_수_없다() {
        // Given: 내 장바구니에서 찾으면 없음 (다른 사람의 상품)
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findByIdAndCartId(999L, cart.getId())).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> cartService.changeQuantity(BUYER_ID, 999L, 5))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    void 장바구니_상품의_선택을_해제할_수_있다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        CartItem item = CartItem.create(cart, 100L, 1);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findByIdAndCartId(1L, cart.getId())).willReturn(Optional.of(item));

        // When
        cartService.changeSelected(BUYER_ID, 1L, false);

        // Then
        assertThat(item.isSelected()).isFalse();
    }

    @Test
    void 장바구니_상품을_전체_선택_해제할_수_있다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        CartItem apple = CartItem.create(cart, 100L, 1);
        CartItem mackerel = CartItem.create(cart, 200L, 2);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findAllByCartId(cart.getId())).willReturn(List.of(apple, mackerel));

        // When
        cartService.changeAllSelected(BUYER_ID, false);

        // Then
        assertThat(List.of(apple, mackerel))
                .extracting("selected")
                .containsOnly(false);
    }

    @Test
    void 장바구니_상품을_하나씩_삭제할_수_있다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        CartItem item = CartItem.create(cart, 100L, 1);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findByIdAndCartId(1L, cart.getId())).willReturn(Optional.of(item));

        // When
        cartService.deleteItem(BUYER_ID, 1L);

        // Then
        then(cartItemRepository).should().delete(item);
    }

    @Test
    void 다른_사람의_장바구니_상품은_삭제할_수_없다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findByIdAndCartId(999L, cart.getId())).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> cartService.deleteItem(BUYER_ID, 999L))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
        then(cartItemRepository).should(never()).delete(any(CartItem.class));
    }

    @Test
    void 선택한_상품을_한_번에_삭제할_수_있다() {
        // Given
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));

        // When
        cartService.deleteSelectedItems(BUYER_ID);

        // Then
        then(cartItemRepository).should().deleteAllByCartIdAndSelectedTrue(cart.getId());
    }
}
