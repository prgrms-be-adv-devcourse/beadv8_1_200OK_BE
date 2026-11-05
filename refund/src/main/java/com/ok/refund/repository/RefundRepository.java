package com.ok.refund.repository;

import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /**
     * 같은 판매자 묶음(주문 ID + 판매자 ID)에서 주어진 상태인 요청의 취소 개수와 뺀 배송비를 주문 상품별로 더한다.
     * 묶음 전부 취소인지, 남은 묶음 금액이 얼마인지, 앞에서 뺀 배송비가 얼마인지 한 번의 조회로 판단하려고 쓴다.
     */
    // TODO(#411): 같은 묶음의 다른 환불을 동시에 승인하면 서로의 처리 중 개수를 못 보고 배송비를 틀리게 정할 수 있다. 묶음 단위로 줄 세우기 필요
    @Query("""
            select new com.ok.refund.domain.CancelledQuantity(r.orderItemId, sum(r.quantity), sum(r.deductedShippingFeeAmount))
            from Refund r
            where r.orderId = :orderId and r.sellerId = :sellerId and r.status in :statuses
            group by r.orderItemId
            """)
    List<CancelledQuantity> sumCancelledByOrderItem(
            @Param("orderId") Long orderId,
            @Param("sellerId") Long sellerId,
            @Param("statuses") Collection<RefundStatus> statuses);
}
