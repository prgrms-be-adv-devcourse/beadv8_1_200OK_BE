package com.ok.wallet.repository;

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
public interface WalletOrderPaymentRepository extends Repository<WalletOrderPayment, Long> {

    // 같은 주문의 처리 기록이 없을 때만 넣고, 넣었으면 1, 이미 있었으면 0을 돌려준다(왜 넣기로 판단하는지는 payForOrder, #360).
    // ON DUPLICATE KEY UPDATE는 MySQL 드라이버 기본 설정에서 중복이어도 1을 돌려줘 구분할 수 없어 IGNORE를 쓴다.
    // IGNORE는 중복 외 오류도 넘기므로 값 검증은 호출하는 쪽이 먼저 한다.
    // BaseTime을 거치지 않아 시각을 직접 받으므로, 엔티티에 NOT NULL 컬럼이 늘면 이 쿼리도 고친다
    @Modifying
    @Query(value = "INSERT IGNORE INTO " + WalletSchema.NAME + """
            .wallet_order_payment (order_id, buyer_id, amount, status, created_at, updated_at)
            VALUES (:orderId, :buyerId, :amount, :status, :now, :now)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId, @Param("buyerId") Long buyerId, @Param("amount") long amount,
                       @Param("status") String status, @Param("now") LocalDateTime now);

    // 이미 있는 처리 기록을 최신 커밋 데이터로 읽는다. 일반 조회는 스냅샷 때문에 다른 트랜잭션이 방금 넣은 기록을 못 볼 수 있다.
    // 기록이 있을 때(insertIfAbsent가 0)만 부르므로 그 행만 잠긴다. 없는 행을 잠그면 빈 구간까지 잠겨 다른 주문의 기록 넣기를 막는다
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<WalletOrderPayment> findForShareByOrderId(Long orderId);
}
