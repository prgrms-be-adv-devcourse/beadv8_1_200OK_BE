package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 충전 건은 상태가 바뀌므로 저장·조회와 조건부 상태 변경만 두고, 삭제 메서드는 두지 않는다.
public interface ChargeJpaRepository extends Repository<Charge, Long> {

    Charge save(Charge charge);

    Optional<Charge> findByChargeNo(String chargeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Charge> findForUpdateByChargeNo(String chargeNo);

    // 일괄 UPDATE는 Auditing을 거치지 않아 수정 시각을 직접 넣는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Charge c
        SET c.status = com.ok.wallet.domain.ChargeStatus.IN_PROGRESS,
            c.paymentKey = :paymentKey,
            c.nextCheckAt = :nextCheckAt,
            c.updatedAt = :now
        WHERE c.chargeNo = :chargeNo AND c.status = com.ok.wallet.domain.ChargeStatus.PENDING
        """)
    int markInProgress(@Param("chargeNo") String chargeNo,
                       @Param("paymentKey") String paymentKey,
                       @Param("now") LocalDateTime now,
                       @Param("nextCheckAt") LocalDateTime nextCheckAt);

    // 일괄 UPDATE는 Auditing을 거치지 않아 수정 시각을 직접 넣는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Charge c
            SET c.status = com.ok.wallet.domain.ChargeStatus.FAILED,
                c.failureCode = :failureCode,
                c.failureMessage = :failureMessage,
                c.failedAt = :now,
                c.updatedAt = :now
            WHERE c.chargeNo = :chargeNo AND c.status = com.ok.wallet.domain.ChargeStatus.PENDING
            """)
    int markFailedIfPending(@Param("chargeNo") String chargeNo,
                            @Param("failureCode") String failureCode,
                            @Param("failureMessage") String failureMessage,
                            @Param("now") LocalDateTime now);

    @Query("""
            SELECT c FROM Charge c
            WHERE c.status = com.ok.wallet.domain.ChargeStatus.IN_PROGRESS AND c.nextCheckAt <= :now
            ORDER BY c.nextCheckAt
            """)
    List<Charge> findDueForRecovery(@Param("now") LocalDateTime now, Limit limit);

    // 일괄 UPDATE는 Auditing을 거치지 않아 수정 시각을 직접 넣는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Charge c
            SET c.nextCheckAt = :nextCheckAt,
                c.checkCount = c.checkCount + 1,
                c.updatedAt = :now
            WHERE c.chargeNo = :chargeNo
              AND c.status = com.ok.wallet.domain.ChargeStatus.IN_PROGRESS
              AND c.nextCheckAt <= :now
            """)
    int claimForRecovery(@Param("chargeNo") String chargeNo,
                         @Param("now") LocalDateTime now,
                         @Param("nextCheckAt") LocalDateTime nextCheckAt);

    @Query("""
            SELECT c.chargeNo FROM Charge c
            WHERE c.status = com.ok.wallet.domain.ChargeStatus.IN_PROGRESS AND c.createdAt < :createdBefore
            ORDER BY c.createdAt
            """)
    List<String> findInProgressChargeNosCreatedBefore(@Param("createdBefore") LocalDateTime createdBefore, Limit limit);

    // 일괄 UPDATE는 Auditing을 거치지 않아 수정 시각을 직접 넣는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Charge c
            SET c.nextCheckAt = NULL,
                c.updatedAt = :now
            WHERE c.chargeNo = :chargeNo AND c.status = com.ok.wallet.domain.ChargeStatus.IN_PROGRESS
            """)
    int holdForReview(@Param("chargeNo") String chargeNo, @Param("now") LocalDateTime now);

    @Query("""
            SELECT c FROM Charge c
            WHERE (c.status = com.ok.wallet.domain.ChargeStatus.IN_PROGRESS AND c.nextCheckAt IS NULL)
               OR (c.status = com.ok.wallet.domain.ChargeStatus.FAILED
                   AND c.approvedAfterFailureAt IS NOT NULL AND c.paymentCanceledAt IS NULL)
            ORDER BY c.createdAt
            """)
    List<Charge> findAdminTargets(Limit limit);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM Charge c WHERE c.memberId = :memberId AND c.status = :status")
    long sumAmountByMemberIdAndStatus(@Param("memberId") Long memberId, @Param("status") ChargeStatus status);
}
