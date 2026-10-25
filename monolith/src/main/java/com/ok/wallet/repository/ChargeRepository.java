package com.ok.wallet.repository;

import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

// 충전 건은 상태가 바뀌므로 저장·조회와 조건부 상태 변경만 두고, 삭제 메서드는 두지 않는다.
public interface ChargeRepository extends Repository<Charge, Long> {
    Charge save(Charge charge);

    Optional<Charge> findByChargeNo(String chargeNo);

    /**
     * 대기 중인 충전 건만 처리 중으로 바꾸고 결제 키를 함께 저장한다. 바뀐 행 수를 반환한다.
     * 같은 건으로 승인 요청이 동시에 와도 한 요청만 1을 받아 토스 승인을 호출한다.
     * 결제 키를 이때 저장해, 승인 결과가 불명확하거나 반영에 실패해 처리 중으로 남아도 복구 시 어떤 결제인지 알 수 있다.
     * 일괄 UPDATE는 Auditing을 거치지 않아 수정 시각을 직접 넣는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Charge c
        SET c.status = com.ok.wallet.domain.ChargeStatus.IN_PROGRESS,
            c.paymentKey = :paymentKey,
            c.updatedAt = :now
        WHERE c.chargeNo = :chargeNo AND c.status = com.ok.wallet.domain.ChargeStatus.PENDING
        """)
    int markInProgress(@Param("chargeNo") String chargeNo,
                       @Param("paymentKey") String paymentKey,
                       @Param("now") LocalDateTime now);

    /**
     * 대기 중인 충전 건만 실패로 바꾸고, 바뀐 행 수를 반환한다.
     * 승인이 먼저 처리 중으로 바꿨다면 0이 되어, 승인된 결제를 실패로 덮어쓰지 않는다.
     * 일괄 UPDATE는 Auditing을 거치지 않아 수정 시각을 직접 넣는다.
     */
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

    // 회원의 특정 상태 충전 금액 합계. 잔액 보유 한도 확인에서 처리 중 충전을 함께 계산할 때 쓴다.
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM Charge c WHERE c.memberId = :memberId AND c.status = :status")
    long sumAmountByMemberIdAndStatus(@Param("memberId") Long memberId, @Param("status") ChargeStatus status);
}
