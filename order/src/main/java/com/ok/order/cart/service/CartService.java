package com.ok.order.cart.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.controller.dto.CartResponse;
import com.ok.order.cart.domain.Cart;
import com.ok.order.cart.domain.CartItem;
import com.ok.order.cart.exception.CartErrorCode;
import com.ok.order.cart.repository.CartItemRepository;
import com.ok.order.cart.repository.CartRepository;
import com.ok.order.cart.service.dto.SelectedCartItem;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final Clock clock;

    // 장바구니 생성 (회원가입 이벤트)
    // 회원가입 직후 API 요청이 먼저 만들었을 수 있어, 존재 확인 없이 "없으면 저장" 한 번으로 처리한다
    @Transactional
    public void createCart(Long memberId) {
        cartRepository.insertIfAbsent(memberId, LocalDateTime.now(clock));
    }

    // 장바구니에 상품 추가
    @Transactional
    public Long addItem(Long memberId, Long productOptionId, int quantity) {
        // TODO 회원 모듈 구매 가능 여부 API 제공 후 연동 → MEMBER_CANNOT_PURCHASE

        // TODO 상품 모듈 옵션 조회 API 연동 후: 판매 중인지, 본인 상품인지 검증

        Cart cart = getOrCreateCart(memberId);

        if (cartItemRepository.existsByCartIdAndProductOptionId(cart.getId(), productOptionId)) {
            throw new RestApiException(CartErrorCode.CART_ITEM_ALREADY_EXISTS);
        }

        CartItem cartItem = CartItem.create(cart, productOptionId, quantity);
        return saveItem(cartItem).getId();
    }

    // 장바구니 리스트 조회
    @Transactional
    public CartResponse getMyCart(Long memberId) {
        Cart cart = getOrCreateCart(memberId);

        List<CartItem> items = cartItemRepository.findAllByCartId(cart.getId());

        return CartResponse.of(cart, items);
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
        Cart cart = getOrCreateCart(memberId);
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
        Cart cart = getOrCreateCart(memberId);
        cartItemRepository.deleteAllByCartIdAndSelectedTrue(cart.getId());
    }

    // 내 장바구니 조회, 없으면 생성
    // 회원가입 리스너나 다른 요청이 동시에 만들 수 있어 save 대신 "없으면 저장"을 쓴다 (save는 유니크 제약 위반으로 500이 난다)
    // 다시 읽을 때는 잠금 조회를 쓴다. 일반 조회는 앞의 조회 시점 스냅샷을 봐서, 다른 트랜잭션이 방금 만든 장바구니가 보이지 않는다
    private Cart getOrCreateCart(Long memberId) {
        Optional<Cart> cart = cartRepository.findByMemberId(memberId);
        if (cart.isPresent()) {
            return cart.get();
        }

        cartRepository.insertIfAbsent(memberId, LocalDateTime.now(clock));

        // 바로 앞에서 저장했거나 다른 트랜잭션이 저장했으므로 없을 수 없다.
        // 없다면 사용자 입력이 아닌 코드·DB의 이상이므로 500으로 드러나도록 IllegalStateException을 던진다
        return cartRepository.findForShareByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException("장바구니 생성 후 조회에 실패했습니다. memberId=" + memberId));
    }

    // 동시 담기 대비 : 유니크 제약 위반을 중복 예외로 변환
    private CartItem saveItem(CartItem cartItem) {
        try {
            return cartItemRepository.saveAndFlush(cartItem);
        } catch (DataIntegrityViolationException e) {
            throw new RestApiException(CartErrorCode.CART_ITEM_ALREADY_EXISTS);
        }
    }

    // 내 장바구니 안의 상품 조회
    private CartItem getMyCartItem(Long memberId, Long cartItemId) {
        Cart cart = getOrCreateCart(memberId);
        return cartItemRepository.findByIdAndCartId(cartItemId, cart.getId())
                .orElseThrow(() -> new RestApiException(CartErrorCode.CART_ITEM_NOT_FOUND));
    }

    // 선택된 장바구니 상품
    public List<SelectedCartItem> getSelectedCartItems(Long memberId) {
        // 회원의 장바구니 찾기
        Optional<Cart> cartOptional = cartRepository.findByMemberId(memberId);

        // 장바구니가 없으면 빈 목록
        if (cartOptional.isEmpty()) {
            return List.of();
        }
        Cart cart = cartOptional.get();

        // 선택된 장바구니 상품들 가져오기
        List<CartItem> items = cartItemRepository.findAllByCartIdAndSelectedTrue(cart.getId());

        // 하나씩 productOptionId와 quantity를 selectedCartItem로 변환
        List<SelectedCartItem> results = new ArrayList<>();
        for (CartItem item : items) {
            results.add(new SelectedCartItem(item.getProductOptionId(), item.getQuantity()));
        }

        return results;
    }

    // 결제 완료된 주문의 상품을 장바구니에서 삭제
    @Transactional
    public void removeOrderItems(Long memberId, List<Long> productOptionIds) {
        Optional<Cart> cartOptional = cartRepository.findByMemberId(memberId);
        if (cartOptional.isEmpty()) {
            return;
        }
        cartItemRepository.deleteAllByCartIdAndProductOptionIdIn(cartOptional.get().getId(), productOptionIds);
    }
}
