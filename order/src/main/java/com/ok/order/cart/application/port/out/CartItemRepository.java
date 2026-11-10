package com.ok.order.cart.application.port.out;

import com.ok.order.cart.domain.CartItem;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 장바구니 상품 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface CartItemRepository {

    boolean existsByCartIdAndProductOptionId(Long cartId, Long productOptionId);

    Optional<CartItem> findByCartIdAndProductOptionId(Long cartId, Long productOptionId);

    /**
     * 같은 옵션이 없으면 넣고, 이미 있으면 기존 수량에 더한다(상한 {@link CartItem#MAX_QUANTITY}에서 멈춤). 다시 담으면 선택 상태를 켠다.
     * 같은 옵션을 동시에 담아도 유니크 위반이나 수량 누락 없이 두 수량이 모두 반영된다.
     * 실행 뒤 영속성 컨텍스트를 비우므로, 이 호출 전에 읽은 엔티티는 다시 조회해서 쓴다.
     *
     * @param now 생성·수정 시각. 엔티티를 거치지 않고 저장해 {@code BaseTime}이 채우지 않으므로 직접 받는다
     */
    void insertOrAddQuantity(Long cartId, Long productOptionId, int quantity, LocalDateTime now);

    List<CartItem> findAllByCartId(Long cartId);

    Optional<CartItem> findByIdAndCartId(Long id, Long cartId);

    void delete(CartItem cartItem);

    void deleteAllByCartIdAndSelectedTrue(Long cartId);

    List<CartItem> findAllByCartIdAndSelectedTrue(Long cartId);

    void deleteAllByCartIdAndProductOptionIdIn(Long cartId, List<Long> productOptionIds);
}
