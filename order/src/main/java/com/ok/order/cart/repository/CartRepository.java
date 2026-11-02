package com.ok.order.cart.repository;

import com.ok.order.OrderSchema;
import com.ok.order.cart.domain.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByMemberId(Long memberId);

    // 장바구니가 없으면 저장하고, 이미 있으면 아무것도 하지 않는다.
    // 회원가입 리스너와 API 요청이 동시에 만들어도 유니크 제약 위반 예외가 나지 않아, 트랜잭션이 롤백 전용이 되지 않는다.
    // 중복일 때만 넘어가고 다른 오류(null 등)는 그대로 실패한다. INSERT IGNORE는 다른 오류도 경고로 바꿔 삼켜서 쓰지 않는다.
    // MySQL 전용 문법이라 네이티브 쿼리로 작성하며, BaseTime을 거치지 않으므로 생성 시각을 직접 받는다.
    // 컬럼을 직접 나열하므로 Cart·BaseTime에 NOT NULL 컬럼이 추가되면 이 쿼리도 함께 수정해야 한다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
    @Modifying
    @Query(value = "INSERT INTO " + OrderSchema.NAME + """
            .cart (member_id, created_at, updated_at)
            VALUES (:memberId, :now, :now)
            ON DUPLICATE KEY UPDATE member_id = member_id
            """, nativeQuery = true)
    void insertIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    // 공유 잠금 조회 - 일반 조회와 달리 트랜잭션의 스냅샷이 아니라 최신 커밋 데이터를 읽어, 다른 트랜잭션이 방금 만든 장바구니도 보인다.
    // 읽기만 하므로 다른 조회를 막지 않는 공유 잠금(FOR SHARE)을 쓴다.
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Cart> findForShareByMemberId(Long memberId);
}
