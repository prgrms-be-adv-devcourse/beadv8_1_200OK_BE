package com.ok.order.cart.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.controller.dto.CartResponse;
import com.ok.order.cart.domain.Cart;
import com.ok.order.cart.domain.CartItem;
import com.ok.order.cart.exception.CartErrorCode;
import com.ok.order.cart.repository.CartItemRepository;
import com.ok.order.cart.repository.CartRepository;
import com.ok.order.cart.service.dto.SelectedCartItem;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
public class CartServiceTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;

    // 한국 시간 2026-11-02 09:00으로 고정
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 9, 0);

    @InjectMocks CartService cartService;

    static final Long BUYER_ID = 1L;
    static final Long SELLER_ID = 99L;

    // ---------- 장바구니 생성 ----------

    @Test
    void 회원가입하면_장바구니를_없을_때만_저장하는_쿼리로_만든다() {
        // when
        cartService.createCart(BUYER_ID);

        // then: 이미 있는지는 DB가 판단하므로 존재 확인 없이 바로 부른다 (중복 무시는 통합 테스트에서 확인)
        then(cartRepository).should().insertIfAbsent(BUYER_ID, NOW);
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
        then(cartItemRepository).should(never()).saveAndFlush(any(CartItem.class));
    }

    @Test
    void 장바구니가_없으면_만들고_담는다() {
        // given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.empty());
        given(cartRepository.findForShareByMemberId(BUYER_ID)).willReturn(Optional.of(Cart.create(BUYER_ID)));
        given(cartItemRepository.saveAndFlush(any(CartItem.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        cartService.addItem(BUYER_ID, 100L, 1);

        // then
        then(cartRepository).should().insertIfAbsent(BUYER_ID, NOW);
        then(cartItemRepository).should().saveAndFlush(any(CartItem.class));
    }

    @Test
    void 동시에_같은_상품을_담아_유니크_제약을_위반하면_이미_담긴_상품_예외로_바꾼다() {
        // given
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.saveAndFlush(any(CartItem.class)))
                .willThrow(new DataIntegrityViolationException("uk_cart_item_cart_option"));

        // when & then
        assertThatThrownBy(() -> cartService.addItem(BUYER_ID, 100L, 1))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_ALREADY_EXISTS));
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
    void 장바구니가_없으면_만들고_빈_목록을_응답한다() {
        // given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.empty());
        given(cartRepository.findForShareByMemberId(BUYER_ID)).willReturn(Optional.of(Cart.create(BUYER_ID)));

        // when
        CartResponse sut = cartService.getMyCart(BUYER_ID);

        // then
        assertThat(sut).isNotNull();
        assertThat(sut.items()).isEmpty();
        then(cartRepository).should().insertIfAbsent(BUYER_ID, NOW);
    }

    @Test
    void 장바구니가_있으면_새로_만들지_않는다() {
        // given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(Cart.create(BUYER_ID)));

        // when
        cartService.getMyCart(BUYER_ID);

        // then: 대부분의 요청은 일반 조회 한 번으로 끝난다
        then(cartRepository).should(never()).insertIfAbsent(any(), any());
        then(cartRepository).should(never()).findForShareByMemberId(any());
    }

    @Test
    void 장바구니를_만든_뒤에도_조회되지_않으면_서버_오류로_드러낸다() {
        // given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.empty());
        given(cartRepository.findForShareByMemberId(BUYER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> cartService.getMyCart(BUYER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("장바구니 생성 후 조회에 실패했습니다. memberId=" + BUYER_ID);
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
        // Given
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

    // ---------- 주문용 선택 상품 ----------

    @Test
    void 주문용으로_선택된_장바구니_상품을_옵션과_수량으로_돌려준다() {
        // given
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));
        given(cartItemRepository.findAllByCartIdAndSelectedTrue(cart.getId()))
                .willReturn(List.of(CartItem.create(cart, 100L, 2)));

        // when
        List<SelectedCartItem> sut = cartService.getSelectedCartItems(BUYER_ID);

        // then
        assertThat(sut).containsExactly(new SelectedCartItem(100L, 2));
    }

    @Test
    void 장바구니가_없으면_주문용_선택_상품은_빈_목록이고_장바구니를_만들지_않는다() {
        // given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.empty());

        // when
        List<SelectedCartItem> sut = cartService.getSelectedCartItems(BUYER_ID);

        // then
        assertThat(sut).isEmpty();
        then(cartRepository).should(never()).insertIfAbsent(any(), any());
    }

    // ---------- 결제 완료 후 장바구니 정리 ----------

    @Test
    void 결제가_완료되면_주문한_옵션을_장바구니에서_삭제한다() {
        // given
        Cart cart = Cart.create(BUYER_ID);
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.of(cart));

        // when
        cartService.removeOrderItems(BUYER_ID, List.of(100L, 200L));

        // then
        then(cartItemRepository).should().deleteAllByCartIdAndProductOptionIdIn(cart.getId(), List.of(100L, 200L));
    }

    @Test
    void 장바구니가_없으면_삭제하지_않고_장바구니도_만들지_않는다() {
        // given
        given(cartRepository.findByMemberId(BUYER_ID)).willReturn(Optional.empty());

        // when
        cartService.removeOrderItems(BUYER_ID, List.of(100L));

        // then
        then(cartItemRepository).should(never()).deleteAllByCartIdAndProductOptionIdIn(any(), any());
        then(cartRepository).should(never()).insertIfAbsent(any(), any());
    }
}