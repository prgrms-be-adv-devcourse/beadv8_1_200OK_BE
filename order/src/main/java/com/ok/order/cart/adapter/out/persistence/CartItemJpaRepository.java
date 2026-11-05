package com.ok.order.cart.adapter.out.persistence;

import com.ok.order.OrderSchema;
import com.ok.order.cart.application.port.out.CartItemRepository;
import com.ok.order.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

// 장바구니 상품 저장소 출력 포트의 JPA 구현. 쿼리 설정은 포트에 둘 수 없어(application은 영속 기술을 모른다) 여기서 다시 선언한다
public interface CartItemJpaRepository extends JpaRepository<CartItem, Long>, CartItemRepository {

    // 확인 → 저장으로 나누면 같은 옵션을 동시에 담을 때 둘 다 "없음"으로 보고 늦은 쪽이 유니크 제약(uk_cart_item_cart_option)에 걸리고,
    // 읽고 → 더해서 → 저장하면 동시에 다시 담을 때 같은 수량을 읽어 한쪽 수량이 사라진다.
    // 한 문장으로 처리하면 DB가 겹침을 판단하고 같은 행을 차례로 고쳐, 동시에 담아도 두 수량이 모두 반영된다.
    // 컬럼을 직접 나열하므로 CartItem·BaseTime에 NOT NULL 컬럼이 추가되면 이 쿼리도 함께 수정해야 한다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
    // 쿼리 뒤 영속성 컨텍스트를 비운다. 같은 트랜잭션에서 이 상품을 이미 읽었다면 캐시의 옛 수량이 다시 읽히기 때문이다
    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO " + OrderSchema.NAME + ".cart_item "
            + "(cart_id, product_option_id, quantity, selected, created_at, updated_at) "
            + "VALUES (:cartId, :productOptionId, :quantity, TRUE, :now, :now) "
            + "ON DUPLICATE KEY UPDATE "
            + "quantity = LEAST(quantity + :quantity, " + CartItem.MAX_QUANTITY + "), "
            + "selected = TRUE, "
            + "updated_at = :now", nativeQuery = true)
    void insertOrAddQuantity(@Param("cartId") Long cartId, @Param("productOptionId") Long productOptionId,
                             @Param("quantity") int quantity, @Param("now") LocalDateTime now);

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    void delete(CartItem cartItem);
}
