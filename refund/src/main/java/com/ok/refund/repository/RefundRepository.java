package com.ok.refund.repository;

import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    /**
     * 환불 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다. 승인·거절·철회처럼 상태를 바꿀 때 쓴다.
     * 트랜잭션의 첫 조회로 불러야 한다. 같은 행을 잠그기 전에 읽어 두면 영속성 컨텍스트에 남은 잠금 전 상태가 그대로 쓰인다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);

    Page<Refund> findAllByBuyerId(Long buyerId, Pageable pageable);

    Page<Refund> findAllBySellerId(Long sellerId, Pageable pageable);

    Page<Refund> findAllBySellerIdAndStatus(Long sellerId, RefundStatus status, Pageable pageable);
}
