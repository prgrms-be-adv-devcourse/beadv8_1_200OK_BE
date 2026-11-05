package com.ok.order.cart.adapter.out.persistence;

import com.ok.order.OrderSchema;
import com.ok.order.cart.application.port.out.CartRepository;
import com.ok.order.cart.domain.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

// 장바구니 저장소 출력 포트의 JPA 구현. 쿼리·잠금 설정은 포트에 둘 수 없어(application은 영속 기술을 모른다) 여기서 다시 선언한다
public interface CartJpaRepository extends JpaRepository<Cart, Long>, CartRepository {

    // 중복일 때만 넘어가고 다른 오류(null 등)는 그대로 실패한다. INSERT IGNORE는 다른 오류도 경고로 바꿔 삼켜서 쓰지 않는다.
    // MySQL 전용 문법이라 네이티브 쿼리로 작성한다.
    // 컬럼을 직접 나열하므로 Cart·BaseTime에 NOT NULL 컬럼이 추가되면 이 쿼리도 함께 수정해야 한다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
    @Override
    @Modifying
    @Query(value = "INSERT INTO " + OrderSchema.NAME + """
            .cart (member_id, created_at, updated_at)
            VALUES (:memberId, :now, :now)
            ON DUPLICATE KEY UPDATE member_id = member_id
            """, nativeQuery = true)
    void insertIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    // 읽기만 하므로 다른 조회를 막지 않는 공유 잠금(FOR SHARE)을 쓴다
    @Override
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Cart> findForShareByMemberId(Long memberId);
}
