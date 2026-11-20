package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.WalletSchema;
import com.ok.wallet.domain.WalletOrderRefundRevert;
import com.ok.wallet.domain.WalletOrderRefundRevertStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 되돌리기 결과는 한 번 기록하면 바뀌지 않으므로 넣기와 조회만 둔다.
public interface WalletOrderRefundRevertJpaRepository extends Repository<WalletOrderRefundRevert, Long> {

    // IGNORE를 쓰는 이유와 시각을 직접 받는 이유는 WalletOrderPaymentJpaRepository.insertIfAbsent와 같다
    @Modifying
    @Query(value = "INSERT IGNORE INTO " + WalletSchema.NAME + """
            .wallet_order_refund_revert (refund_id, order_id, buyer_id, amount, status, failure_reason, created_at, updated_at)
            VALUES (:refundId, :orderId, :buyerId, :amount, :status, :failureReason, :now, :now)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("refundId") Long refundId, @Param("orderId") Long orderId, @Param("buyerId") Long buyerId,
                       @Param("amount") long amount, @Param("status") String status,
                       @Param("failureReason") String failureReason, @Param("now") LocalDateTime now);

    Optional<WalletOrderRefundRevert> findByRefundId(Long refundId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<WalletOrderRefundRevert> findForShareByRefundId(Long refundId);

    // 반환 합계에서 다시 가져온 환불을 빼려고 읽는다. 잠그는 이유는 WalletOrderRefundJpaRepository.findForShareByOrderIdAndStatus와 같다
    @Lock(LockModeType.PESSIMISTIC_READ)
    List<WalletOrderRefundRevert> findForShareByOrderIdAndStatus(Long orderId, WalletOrderRefundRevertStatus status);
}
