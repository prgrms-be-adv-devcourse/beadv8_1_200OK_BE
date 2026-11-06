package com.ok.refund.application.port.out;

import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 환불 도메인을 저장·조회하는 출력 포트. 구현은 {@code adapter/out/persistence}의 JPA 어댑터다.
 * 저장은 변경 감지에 기대지 않고 명시적으로 한다(#481). 새 행은 {@link #saveNew}, 상태 변경은 {@link #update}를 부른다.
 * 둘로 나눈 이유: 새 행은 ID 생성과 진행 중 중복(UNIQUE) 감지가 필요하고, 수정은 같은 트랜잭션에서 잠근 행에만 해야 해서 성격이 다르다.
 */
public interface RefundRepository {

    /**
     * 새 환불을 바로 저장하고(INSERT를 즉시 보냄) ID와 생성 시각이 채워진 새 도메인을 돌려준다. 넘긴 객체는 바뀌지 않는다.
     *
     * @throws com.ok.common.exception.RestApiException 같은 주문 상품에 진행 중인 요청이 있으면 {@code REFUND_ALREADY_IN_PROGRESS}
     */
    Refund saveNew(Refund refund);

    /**
     * 도메인의 바뀌는 칸(상태, 거절 사유, 결정 시각, 진행 중 칸, 배송비 세 칸, 실패 단계·이유)을 저장한다.
     * 같은 트랜잭션에서 {@link #findByIdForUpdate}로 잠가 불러온 환불에만 부른다. 실제 UPDATE는 트랜잭션 커밋 때 나간다.
     * 같은 트랜잭션에서 여러 번 불러도 된다. 앞선 변경이 중간 조회의 자동 flush로 먼저 나가도, 그 행의 쓰기 잠금은 커밋까지 이 트랜잭션에 있다.
     *
     * @throws IllegalStateException 이 트랜잭션에서 불러오지 않았거나, 불러왔어도 잠금 조회({@link #findByIdForUpdate})가 아니라
     *                               {@link #findById}처럼 잠그지 않고 불러온 환불이면 (부르는 쪽 버그. 잠그지 않은 행을 덮어쓰지 않게 막는다)
     */
    void update(Refund refund);

    Optional<Refund> findById(Long id);

    /**
     * 환불 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다. 승인·거절·철회·실패 기록처럼 상태를 바꿀 때 쓴다.
     * 트랜잭션에서 그 행의 첫 조회로 불러야 한다. 같은 행을 잠그기 전에 읽어 두면 잠금 전 상태가 그대로 쓰인다.
     */
    Optional<Refund> findByIdForUpdate(Long id);

    /**
     * 같은 판매자 묶음(주문 ID + 판매자 ID)에서 승인 때 잠글 환불 ID를 오름차순으로 조회한다.
     * 주어진 상태인 행과 이번 환불만 고른다. 잠그지 않는 읽기이고, 뒤이은 {@link #findByIdForUpdate}가 행마다 잠근다.
     */
    List<Long> findLockTargetIds(Long orderId, Long sellerId, Long refundId, Collection<RefundStatus> statuses);

    Page<Refund> findAllByBuyerId(Long buyerId, Pageable pageable);

    Page<Refund> findAllBySellerId(Long sellerId, Pageable pageable);

    Page<Refund> findAllBySellerIdAndStatus(Long sellerId, RefundStatus status, Pageable pageable);

    /**
     * 같은 판매자 묶음에서 주어진 상태인 요청의 취소 개수와 뺀 배송비를 주문 상품별로 더한다.
     * 승인할 때는 묶음 잠금을 얻은 뒤 READ COMMITTED로 불러야, 먼저 끝난 다른 승인의 커밋까지 보인다.
     */
    List<CancelledQuantity> sumCancelledByOrderItem(Long orderId, Long sellerId, Collection<RefundStatus> statuses);
}
