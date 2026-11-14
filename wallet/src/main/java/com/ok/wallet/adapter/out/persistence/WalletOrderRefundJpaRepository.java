package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.WalletSchema;
import com.ok.wallet.domain.WalletOrderRefund;
import com.ok.wallet.domain.WalletOrderRefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 반환 결과는 한 번 기록하면 바뀌지 않으므로 넣기와 조회만 둔다.
public interface WalletOrderRefundJpaRepository extends Repository<WalletOrderRefund, Long> {

    // IGNORE를 쓰는 이유와 시각을 직접 받는 이유는 WalletOrderPaymentJpaRepository.insertIfAbsent와 같다
    @Modifying
    @Query(value = "INSERT IGNORE INTO " + WalletSchema.NAME + """
            .wallet_order_refund (refund_id, order_id, buyer_id, amount, status, failure_reason, created_at, updated_at)
            VALUES (:refundId, :orderId, :buyerId, :amount, :status, :failureReason, :now, :now)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("refundId") Long refundId, @Param("orderId") Long orderId, @Param("buyerId") Long buyerId,
                       @Param("amount") long amount, @Param("status") String status,
                       @Param("failureReason") String failureReason, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<WalletOrderRefund> findForShareByRefundId(Long refundId);

    // 합계를 SQL로 바로 구하지 않고 행을 잠금 조회한다. 잠금은 @Lock 하나로 적는다는 구조 규칙(ArchitectureTest)을 지키려는 것이고,
    // 한 주문의 환불은 상품 수만큼이라 행이 적다
    @Lock(LockModeType.PESSIMISTIC_READ)
    List<WalletOrderRefund> findForShareByOrderIdAndStatus(Long orderId, WalletOrderRefundStatus status);
}
