package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.DecideRefundUseCase;
import com.ok.refund.application.port.in.RefundDecisionCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundShippingFeePolicy;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매자가 본인 상품의 환불 요청을 승인하거나 거절한다.
 * 승인은 주문 조회(구간 밖)와 묶음 잠금·판단·저장(READ COMMITTED 구간)으로 나뉘고, 거절은 주문을 부르지 않아 구간 하나로 끝난다.
 */
@Service
public class DecideRefundService implements DecideRefundUseCase {

    // 승인 때 잠글 상태. 이 상태의 행만 처리 중으로 바뀌거나 이미 처리 중이라 집계를 바꿀 수 있다. 끝난 행(거절·철회)은 잠그지 않는다
    private static final List<RefundStatus> LOCK_TARGET_STATUSES = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);
    // 승인 때 이미 출고됐거나 취소된 상품을 자동 거절할 때 구매자에게 보이는 사유
    static final String SHIPPED_REJECT_REASON = "이미 출고됐거나 취소된 상품이라 취소할 수 없습니다.";

    private final OrderQueryPort orderQueryPort;
    private final RefundPersistencePort refundPersistencePort;
    private final Clock clock;
    // 승인 전 확인 조회용. 잠그지 않고 읽기만 한다
    private final TransactionTemplate readOnlyTransaction;
    // 거절용. 다른 결정과 같은 기본 설정이다
    private final TransactionTemplate transaction;
    // 승인용. 묶음 잠금 뒤 최신 값을 읽어야 해서 READ COMMITTED로 연다(approve 설명 참고)
    private final TransactionTemplate approveTransaction;

    public DecideRefundService(OrderQueryPort orderQueryPort, RefundPersistencePort refundPersistencePort, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.orderQueryPort = orderQueryPort;
        this.refundPersistencePort = refundPersistencePort;
        this.clock = clock;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.transaction = new TransactionTemplate(transactionManager);
        this.approveTransaction = new TransactionTemplate(transactionManager);
        this.approveTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 판매자가 본인 상품의 요청을 승인하거나 거절한다.
     * 승인은 같은 묶음의 배송비를 판단해야 해서 주문을 다시 조회한다. 순서: 환불을 읽어 주문 ID를 얻음 → 주문 조회(트랜잭션 밖)
     * → 잠그고 판단·저장. 앞의 두 단계 사이에 철회되면 마지막 단계가 바뀐 상태를 보고 막는다.
     * 거절은 쓸 주문 값이 없어 주문을 부르지 않는다. 주문이 느리거나 장애여도 거절은 막히지 않는다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND},
     *                          요청됨이 아니면 {@code INVALID_REFUND_STATE},
     *                          승인 때 주문 호출이 실패하면 {@code ORDER_API_FAILED}, 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                          거절인데 사유가 비었으면 {@code REJECT_REASON_REQUIRED}
     */
    @Override
    public RefundResult decide(RefundDecisionCommand command) {
        // 예전 방식 enum switch는 컴파일러가 모든 값을 다뤘는지 보지 않아, 결정이 늘었는데 여기를 빠뜨리면 아무것도 바꾸지 않고
        // 성공으로 응답한다. case null을 두면 새 방식 switch가 되어 모든 값을 다루지 않으면 컴파일 오류가 난다(default를 두지 않는 이유).
        // null은 요청 DTO가 막으므로 여기 오면 버그다
        return switch (command.decision()) {
            case APPROVE -> approve(command.refundId(), command.sellerId());
            case REJECT -> reject(command.refundId(), command.sellerId(), command.rejectReason());
            case null -> throw new IllegalArgumentException("환불 결정은 필수입니다.");
        };
    }

    private RefundResult approve(Long refundId, Long sellerId) {
        // 승인 전에 주문을 조회할 주문 ID를 얻는다. 잠그지 않는다. 주문 호출 동안 행을 잠가 두지 않으려는 것이고,
        // 상태는 승인 구간이 잠근 뒤 다시 확인한다
        RefundResult refund = readOnlyTransaction.execute(status -> findSellerRefund(refundId, sellerId));
        // 이미 끝난 요청이면 주문을 부르지 않고 바로 409로 돌려준다. 주문 장애 때 409 대신 503이 나가지 않게 하려는 것이다.
        // 최종 확인은 아니다. 이 확인과 잠금 사이에 철회될 수 있어 승인 구간이 잠근 뒤 다시 본다
        if (!RefundStatus.REQUESTED.name().equals(refund.status())) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
        // 주문 조회는 어느 구간에도 넣지 않는다(모듈 규칙 3절). 느린 주문 때문에 DB 연결과 행 잠금을 오래 잡지 않으려고
        RefundableOrderItem orderItem = orderQueryPort.getOrderItem(refund.orderId(), refund.orderItemId());
        return approve(refundId, sellerId, orderItem);
    }

    /**
     * 판매자가 승인한다. 같은 묶음의 처리 중 요청을 모아 배송비를 판단하고, 받을 수 있으면 "처리 중"으로 바꾸며 배송비를 저장한다.
     * 받을 수 없으면(취소 금액이 뺄 배송비보다 작음) 사유를 넣어 자동 거절한다. 요청됨으로 남기면 구매자가 이유를 모르고 요청도 쌓이기 때문이다.
     * 이미 출고됐거나 취소된 상품이면 배송비를 판단하지 않고 같은 이유로 자동 거절한다.
     *
     * <p>같은 판매자 묶음의 승인을 한 줄로 세운다(#411). 묶음에서 진행 중(요청됨·처리 중)인 환불과 이번 환불의 ID를 먼저 읽고,
     * ID 오름차순으로 한 건씩 잠근 뒤 집계한다. 끝난 행(거절·철회)은 다시 처리 중이 될 수 없어 잠그지 않는다.
     * 이번 환불 행만 잠그면 같은 묶음의 다른 환불을 동시에 승인할 때 서로를 "아직 요청됨"으로 읽어,
     * 아무도 배송비를 못 받거나 기본 배송비를 두 번 뺀다. 묶음의 진행 중 행을 함께 잠그면 뒤에 온 승인은 앞 승인이 커밋할 때까지 기다린다.
     * 구매자 철회와 겹쳐도 같은 행 잠금이라 먼저 잠근 쪽만 성공한다.
     *
     * <p>READ COMMITTED 구간에서 실행한다. 기본(REPEATABLE READ)은 트랜잭션의 첫 조회(잠글 ID 읽기) 때 모습을 끝까지 보여 주므로,
     * 잠금을 기다리는 동안 앞 승인이 저장한 "처리 중"이 집계에 보이지 않아 줄을 세워도 배송비가 틀린다.
     * 반드시 이 구간이 트랜잭션을 새로 시작해야 하므로 트랜잭션 밖에서만 부른다({@link #decide}가 주문 조회 뒤 부른다).
     * 패키지 안에서만 연다: 주문 정보를 받는 이 단계만 따로 검증하는 테스트가 쓴다.
     *
     * @param orderItem 승인 직전에 주문에서 다시 받은 주문 상품 정보. 출고 여부와 배송비 값을 쓴다.
     *                  묶음을 이 값의 주문 ID로 찾으므로 환불의 주문 ID로 조회한 값이어야 한다. 다른 주문 값이면 묶음에 이 환불이 없어
     *                  {@code REFUND_NOT_FOUND}가 된다({@link #decide}가 환불의 주문 ID로 조회한다)
     * @throws RestApiException      없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     * @throws IllegalStateException 주문 정보가 다른 주문 상품이면, 이미 열린 트랜잭션 안에서 부르면 (부르는 쪽 버그),
     *                               주문 상태가 null이거나 모르는 값이면 (주문 모듈 쪽 문제, 요청됨 그대로 남는다)
     */
    RefundResult approve(Long refundId, Long sellerId, RefundableOrderItem orderItem) {
        requireNoOuterTransaction();
        return approveTransaction.execute(status -> approveLocked(refundId, sellerId, orderItem));
    }

    private RefundResult approveLocked(Long refundId, Long sellerId, RefundableOrderItem orderItem) {
        Refund refund = lockBundleAndFind(refundId, sellerId, orderItem.orderId());
        // 도메인의 approve도 상태를 보지만, 배송비 판단보다 먼저 막아야 한다. 이미 처리 중인 요청은 자기 개수가 집계에 들어가
        // 판단이 "개수 초과"(500)로 깨지므로 그 전에 409로 돌려준다
        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
        if (!refund.getOrderItemId().equals(orderItem.orderItemId())) {
            throw new IllegalStateException("환불과 다른 주문 상품 정보로 승인할 수 없습니다. refundId=" + refundId);
        }
        // 요청 때 출고 전이었어도 승인 사이에 판매자가 출고했을 수 있다. 출고·취소된 상품은 취소로 돌려줄 수 없다.
        // 상태 확인 뒤에 보는 이유: 이미 철회·거절·승인된 요청은 출고 여부와 상관없이 처리할 수 없다는 것이 진짜 이유라 그 응답(409 INVALID_REFUND_STATE)이 먼저다.
        // 에러로 막지 않고 자동 거절하는 이유: 요청됨으로 남기면 구매자는 이유를 모른 채 기다리고, 판매자가 따로 거절해야 끝난다
        LocalDateTime now = LocalDateTime.now(clock);
        if (orderItem.isNoLongerCancellable()) {
            // 배송비 판단은 하지 않는다. 취소할 수 없는 상품이라 뺄 배송비도 없고, 배송비 칸은 0으로 남는다
            refund.reject(SHIPPED_REJECT_REASON, now);
            // 도메인을 바꾼 것만으로는 저장되지 않는다(#481, 명시적 저장). 상태를 바꾼 경로마다 update를 불러야 한다
            refundPersistencePort.update(refund);
            return RefundResult.from(refund);
        }
        // 출고 전(PREPARING)이 아닌데 아는 상태도 아니면(null, 새 상태) 자동 거절하지 않고 서버 오류(500)로 던진다.
        // 주문 모듈의 버그나 계약 변경인데 거절로 끝내면 구매자의 요청이 그 잘못으로 조용히 사라진다.
        // 아무것도 저장하지 않으므로 환불은 요청됨 그대로 남아 고친 뒤 다시 승인할 수 있다
        if (!orderItem.isCancellable()) {
            throw new IllegalStateException("알 수 없는 주문 상품 상태라 승인할 수 없습니다. refundId=" + refundId
                    + ", status=" + orderItem.status());
        }
        // 집계는 이 환불을 처리 중으로 바꾸고 저장하기 전에 한다. 예전에는 엔티티를 바꾸는 순간 자동 flush로 이번 환불이 집계에
        // 들어가 정책이 이번 개수를 또 더하는("개수 초과" 500) 위험이 있었다. 이제는 update를 불러야 엔티티가 바뀌어 구조적으로 안전하지만,
        // "판단한 뒤 바꾸고 저장한다"는 순서가 읽기에도 자연스러워 그대로 둔다
        // 같은 묶음에서 처리 중인 요청만 센다. 판단할 환불은 아직 요청됨이라 집계에 들어가지 않는다
        List<CancelledQuantity> cancelled = refundPersistencePort.sumCancelledByOrderItem(
                refund.getOrderId(), refund.getSellerId(), RefundShippingFeePolicy.CANCELLING_STATUSES);
        RefundShippingFee fee = RefundShippingFeePolicy.decide(refund, orderItem.bundle(), cancelled);
        if (fee.allowed()) {
            refund.approve(now, fee);
        } else {
            // 판매자 거절과 같은 상태로 남긴다. 상태를 따로 두지 않는 이유: 구매자에게는 "거절, 사유"로 충분하고 다시 요청할 수 있다
            refund.reject(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED.getMessage(), now);
        }
        refundPersistencePort.update(refund);
        return RefundResult.from(refund);
    }

    // 거절은 쓸 주문 값이 없어 주문을 부르지 않는다. 주문이 느리거나 장애여도 거절은 막히지 않는다.
    // 승인과 같은 이유로 행을 잠근다. 남은 예외는 decide 설명을 따른다(REFUND_NOT_FOUND, REJECT_REASON_REQUIRED, INVALID_REFUND_STATE)
    private RefundResult reject(Long refundId, Long sellerId, String rejectReason) {
        return transaction.execute(status -> {
            Refund refund = findSellerRefundForUpdate(refundId, sellerId);
            refund.reject(rejectReason, LocalDateTime.now(clock));
            refundPersistencePort.update(refund);
            return RefundResult.from(refund);
        });
    }

    // 승인은 이 구간이 트랜잭션을 새로 시작할 때만 진행한다. 바깥 트랜잭션에 합류하면 두 가지가 조용히 깨진다.
    // 1) 스프링이 READ COMMITTED 지정을 경고 없이 무시한다(바깥이 기본 격리 수준이면 잠금 뒤에도 옛 값을 읽음)
    // 2) 바깥이 READ COMMITTED여도, 바깥에서 같은 환불을 먼저 읽어 뒀다면 잠금 조회가 그 옛 엔티티를 돌려줘 잠그기 전 상태로 판단한다
    // 격리 수준만 검사하면 2)를 놓쳐서 "새로 시작하는지"를 본다. 구간을 열기 전에 열린 트랜잭션이 없으면 TransactionTemplate이
    // 트랜잭션을 새로 시작하므로 격리 수준도 적용되고 영속성 컨텍스트도 새것이다.
    // 단, "영속성 컨텍스트도 새것"은 OSIV(open-in-view)가 꺼져 있다는 전제다. 켜면 앞의 확인 조회에서 읽은 환불이 승인 구간까지 남아
    // 잠금 조회가 그 옛 객체를 돌려주고, 이 검사로는 막지 못한다(지금은 refund·monolith 설정 모두 open-in-view: false, 통합 테스트로 확인).
    // 예전 @Transactional 방식은 같은 클래스 안 this 호출이 프록시를 비껴가 검사 기준이 흔들릴 수 있었지만,
    // 이제는 이 메서드가 구간을 직접 열기 직전에 보므로 부르는 경로와 상관없이 같은 판단을 한다.
    // 단위 테스트의 가짜 트랜잭션 매니저는 트랜잭션 동기화를 켜지 않아 열린 트랜잭션으로 보이지 않으므로 검사를 통과한다
    private static void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("환불 승인은 이미 열린 트랜잭션 안에서 부를 수 없습니다. 새 트랜잭션으로 실행해야 합니다.");
        }
    }

    // 판매자 본인 상품의 환불 한 건을 잠그지 않고 읽는다. 남의 요청은 있는지도 알려 주지 않으려고 없는 것과 같게 막는다
    private RefundResult findSellerRefund(Long refundId, Long sellerId) {
        return refundPersistencePort.findById(refundId)
                .filter(found -> found.isOwnedBySeller(sellerId))
                .map(RefundResult::from)
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
    }

    // 묶음(주문 + 판매자)의 진행 중 환불과 이번 환불을 ID 오름차순으로 한 건씩 잠그고 그중 이번 환불을 돌려준다.
    // 판매자 ID로 묶음을 찾으므로 남의 요청이나 없는 요청은 목록에 들어 있지 않아 없는 것과 같게 막는다
    private Refund lockBundleAndFind(Long refundId, Long sellerId, Long orderId) {
        List<Long> lockTargetIds = refundPersistencePort.findLockTargetIds(orderId, sellerId, refundId, LOCK_TARGET_STATUSES);
        if (!lockTargetIds.contains(refundId)) {
            throw new RestApiException(RefundErrorCode.REFUND_NOT_FOUND);
        }
        // 한 번의 IN 조회로 잠그면 잠금 순서가 DB 실행 계획에 달려 보장되지 않는다. 한 건씩 ID 오름차순으로 잠가야
        // 승인끼리 항상 같은 순서로 기다려 서로 반대 순서로 기다리는 교착 상태가 생기지 않는다. 묶음 행은 많지 않아 쿼리 수는 문제되지 않는다
        // 잠금을 기다리다 DB 잠금 대기 시간(innodb_lock_wait_timeout)을 넘기면 예외를 그대로 던져 500으로 드러낸다.
        // 요청 저장(#409)과 같은 기준이다: 승인 트랜잭션에는 외부 호출이 없어 정상이면 잠금을 짧게 쥐므로, 오래 기다렸다면
        // 사용자 잘못이 아니라 DB나 다른 트랜잭션의 이상이다. 저장된 것이 없어 환불은 요청됨 그대로 남고 다시 승인하면 된다
        // 환불 행은 지우지 않아 ID를 읽은 뒤 잠그는 사이에 사라질 일은 없다. 그래도 사라지면 없는 요청으로 막는다.
        Refund target = null;
        for (Long id : lockTargetIds) {
            Refund locked = refundPersistencePort.findByIdForUpdate(id)
                    .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
            if (id.equals(refundId)) {
                target = locked;
            }
        }
        // 위에서 목록에 이번 환불이 있는 것을 확인했으므로 여기서 target은 항상 채워져 있다.
        // 그 전제를 주석에만 맡기지 않고 확인한다. 나중에 위 코드를 고치다 전제가 깨지면 approve 안쪽 깊은 곳의 NPE 대신
        // 여기서 바로 원인이 드러나게 한다. 사용자 입력과 무관한 코드 버그라 표준 예외(500)로 던진다(exception.md)
        if (target == null) {
            throw new IllegalStateException("잠근 환불 목록에서 이번 환불을 찾지 못했습니다. refundId=" + refundId);
        }
        return target;
    }

    // 판매자 결정은 트랜잭션의 첫 조회로 잠근다(findByIdForUpdate 설명 참고). 남의 요청은 있는지도 알려 주지 않으려고 없는 것과 같게 막는다
    private Refund findSellerRefundForUpdate(Long refundId, Long sellerId) {
        return refundPersistencePort.findByIdForUpdate(refundId)
                .filter(found -> found.isOwnedBySeller(sellerId))
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
    }
}
