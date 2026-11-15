package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.WalletSchema;
import com.ok.wallet.domain.WalletOrderPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

// 결제 결과는 한 번 기록하면 바뀌지 않으므로 넣기와 조회만 둔다.
public interface WalletOrderPaymentJpaRepository extends Repository<WalletOrderPayment, Long> {

    // ON DUPLICATE KEY UPDATE는 MySQL 드라이버 기본 설정에서 중복이어도 1을 돌려줘 구분할 수 없어 IGNORE를 쓴다.
    // BaseTime을 거치지 않아 시각을 직접 받으므로, 엔티티에 NOT NULL 컬럼이 늘면 이 쿼리도 고친다
    @Modifying
    @Query(value = "INSERT IGNORE INTO " + WalletSchema.NAME + """
            .wallet_order_payment (order_id, buyer_id, amount, status, created_at, updated_at)
            VALUES (:orderId, :buyerId, :amount, :status, :now, :now)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId, @Param("buyerId") Long buyerId, @Param("amount") long amount,
                       @Param("status") String status, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<WalletOrderPayment> findForShareByOrderId(Long orderId);

    Optional<WalletOrderPayment> findByOrderId(Long orderId);
}
