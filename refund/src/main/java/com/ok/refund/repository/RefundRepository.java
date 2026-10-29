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
     * 환불 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다. 철회·거절처럼 한 건만 바꿀 때 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);

    /**
     * 같은 판매자 묶음(주문, 판매자)의 환불 ID만 조회한다.
     * 엔티티를 만들지 않아야 뒤이은 잠금 조회가 이 행들의 첫 로딩이 되어, 영속성 컨텍스트에 잠금 전 상태가 남지 않는다.
     */
    @Query("select r.id from Refund r where r.orderId = :orderId and r.sellerId = :sellerId")
    List<Long> findIdsByOrderIdAndSellerId(@Param("orderId") Long orderId, @Param("sellerId") Long sellerId);

    /**
     * 주어진 환불 행들을 ID 순서로 쓰기 잠금한다. 같은 묶음 승인을 한 줄로 세울 때 쓴다.
     * 항상 ID 순서로 잠가, 같은 묶음을 동시에 승인해도 서로 반대 순서로 기다리는 교착이 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id in :ids order by r.id")
    List<Refund> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);

    /**
     * 같은 판매자 묶음에서 주문 상품별로 주어진 상태들의 취소 개수 합을 구한다. 묶음 전부 취소 판단에 쓴다.
     * 승인할 때 잠금을 얻은 뒤 READ COMMITTED로 불러야, ID를 읽은 뒤 끼어든 다른 승인의 커밋까지 보인다.
     */
    @Query("select new com.ok.refund.domain.CancelledQuantity(r.orderItemId, sum(r.quantity)) from Refund r "
            + "where r.orderId = :orderId and r.sellerId = :sellerId and r.status in :statuses group by r.orderItemId")
    List<CancelledQuantity> sumQuantityByOrderItem(
            @Param("orderId") Long orderId,
            @Param("sellerId") Long sellerId,
            @Param("statuses") Collection<RefundStatus> statuses
    );

    /**
     * 주문 상품에서 이미 완료된 취소 개수 합. 요청 때 남은 취소 가능 개수를 계산한다.
     */
    @Query("select coalesce(sum(r.quantity), 0) from Refund r where r.orderItemId = :orderItemId and r.status = :status")
    long sumQuantityByOrderItemIdAndStatus(@Param("orderItemId") Long orderItemId, @Param("status") RefundStatus status);

    Page<Refund> findAllByBuyerId(Long buyerId, Pageable pageable);

    Page<Refund> findAllBySellerId(Long sellerId, Pageable pageable);

    Page<Refund> findAllBySellerIdAndStatus(Long sellerId, RefundStatus status, Pageable pageable);
}
