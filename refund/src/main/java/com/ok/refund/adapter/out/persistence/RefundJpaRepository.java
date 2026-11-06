package com.ok.refund.adapter.out.persistence;

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

public interface RefundJpaRepository extends JpaRepository<Refund, Long> {

    /**
     * 환불 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다. 승인·거절·철회처럼 상태를 바꿀 때 쓴다.
     * 트랜잭션의 첫 조회로 불러야 한다. 같은 행을 잠그기 전에 읽어 두면 영속성 컨텍스트에 남은 잠금 전 상태가 그대로 쓰인다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);

    /**
     * 같은 판매자 묶음(주문 ID + 판매자 ID)에서 승인 때 잠글 환불 ID를 오름차순으로 조회한다.
     * 주어진 상태(진행 중)인 행과 이번 환불만 고른다. 끝난 행(거절·철회)은 다시 처리 중이 될 수 없어 집계를 바꾸지 않으므로 잠그지 않는다.
     * 그래야 같은 상품을 거절·철회 뒤 여러 번 다시 요청해 행이 쌓여도 잠글 행이 늘지 않는다.
     * 이번 환불은 상태와 상관없이 넣는다. 그사이 철회됐으면 잠근 뒤 상태 확인에서 409로 막아야 하는데, 빠지면 404가 된다.
     * 엔티티가 아니라 ID만 읽는 이유: 뒤이은 잠금 조회가 이 행들의 첫 로딩이어야 영속성 컨텍스트에 잠금 전 상태가 남지 않는다.
     * 인덱스: (order_id, seller_id) 인덱스 {@code idx_refund_order_id_seller_id}(마이그레이션 V202611050900, #410)로 묶음 행만 읽는다.
     * 묶음 행은 주문 상품 수 정도라 상태 조건과 정렬은 그 몇 행 안에서 끝나고, 테이블 전체를 훑지 않는다.
     * 이 조회는 잠그지 않는 읽기라 범위 잠금도 걸지 않는다. 잠금은 뒤이은 findByIdForUpdate가 행마다 건다
     */
    @Query("""
            select r.id
            from Refund r
            where r.orderId = :orderId and r.sellerId = :sellerId and (r.status in :statuses or r.id = :refundId)
            order by r.id
            """)
    List<Long> findLockTargetIds(
            @Param("orderId") Long orderId,
            @Param("sellerId") Long sellerId,
            @Param("refundId") Long refundId,
            @Param("statuses") Collection<RefundStatus> statuses);

    Page<Refund> findAllByBuyerId(Long buyerId, Pageable pageable);

    Page<Refund> findAllBySellerId(Long sellerId, Pageable pageable);

    Page<Refund> findAllBySellerIdAndStatus(Long sellerId, RefundStatus status, Pageable pageable);

    /**
     * 같은 판매자 묶음(주문 ID + 판매자 ID)에서 주어진 상태인 요청의 취소 개수와 뺀 배송비를 주문 상품별로 더한다.
     * 묶음 전부 취소인지, 남은 묶음 금액이 얼마인지, 앞에서 뺀 배송비가 얼마인지 한 번의 조회로 판단하려고 쓴다.
     */
    // 승인할 때는 묶음 잠금(findLockTargetIds의 행들을 findByIdForUpdate로)을 얻은 뒤 READ COMMITTED로 불러야, 먼저 끝난 다른 승인의 커밋까지 보인다
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
