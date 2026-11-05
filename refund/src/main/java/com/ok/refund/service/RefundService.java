package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundShippingFeePolicy;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 환불 요청의 DB 작업을 맡는다. 주문 모듈 호출은 {@link RefundFacade}가 하고, 이 서비스는 짧은 트랜잭션으로 확인과 저장만 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundService {

    // 마이그레이션의 제약 이름과 같아야 한다. 이 이름으로 "진행 중 요청 중복"과 다른 무결성 오류를 구분한다
    private static final String ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT = "uk_refund_active_order_item_id";
    // 표준 SQLState의 "직렬화 실패". MySQL은 교착 상태(1213)를 이 값으로 알린다
    private static final String DEADLOCK_SQL_STATE = "40001";
    // 배송 목록과 같은 페이지 크기. 클라이언트가 바꿀 수 없다
    private static final int PAGE_SIZE = 25;
    // 최신순. 같은 시각이면 나중에 만든 요청이 먼저 오도록 ID를 함께 쓴다(페이지 사이에 순서가 흔들리지 않게)
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    // 배송비 판단에서 "이미 취소된 것"으로 보는 상태. 승인된 요청은 되돌리지 않으므로 취소된 것으로 센다
    // TODO(#414): 완료 상태가 생기면 함께 센다. 처리 중을 완료로 바꾸는 처리도 승인처럼 묶음 잠금(lockBundleAndFind)을 거쳐야 한다
    // 묶음 잠금은 잠글 ID를 읽은 뒤 새로 들어온 요청을 잠그지 않는다. 그래도 되는 건 새 요청이 요청됨이고 요청됨은 여기서 세지 않기 때문이다.
    // 이 목록에 요청됨을 넣으면 그 전제가 깨져, 잠그지 않은 행이 집계를 바꿀 수 있다
    private static final List<RefundStatus> CANCELLING_STATUSES = List.of(RefundStatus.PROCESSING);
    // 승인 때 잠글 상태. 이 상태의 행만 처리 중으로 바뀌거나 이미 처리 중이라 집계를 바꿀 수 있다. 끝난 행(거절·철회)은 잠그지 않는다
    private static final List<RefundStatus> LOCK_TARGET_STATUSES = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);

    private final RefundRepository refundRepository;
    private final Clock clock;

    /**
     * 구매자의 취소 요청을 "요청됨"으로 저장한다. 요청만으로는 주문·배송·지갑·상품이 바뀌지 않는다.
     * 같은 주문 상품에 진행 중인 요청이 있으면 {@code active_order_item_id} UNIQUE가 막는다.
     * 먼저 조회해 확인하지 않는 이유: 두 요청이 동시에 오면 둘 다 "없음"으로 보고 통과할 수 있어, DB 제약이 마지막에 막아야 한다.
     *
     * @param orderItem 주문 모듈에서 받은 주문 상품 정보
     * @throws RestApiException 남의 주문이면 {@code ORDER_ITEM_NOT_FOUND}, 출고 전이 아니면 {@code ORDER_ITEM_NOT_CANCELLABLE},
     *                          산 개수보다 많으면 {@code REFUND_QUANTITY_EXCEEDED}, 개수가 1 미만이면 {@code INVALID_REFUND_QUANTITY},
     *                          진행 중인 요청이 있으면 {@code REFUND_ALREADY_IN_PROGRESS},
     *                          무료배송 기준이 깨져 뺄 배송비보다 취소 금액이 작으면 {@code PARTIAL_CANCEL_NOT_ALLOWED}
     */
    @Transactional
    public RefundResult request(RefundRequestCommand command, RefundableOrderItem orderItem) {
        // 남의 주문은 "있지만 네 것이 아님"을 알려 주지 않으려고 없는 것과 같게 응답한다
        if (!orderItem.buyerId().equals(command.buyerId())) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        if (!orderItem.isCancellable()) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_CANCELLABLE);
        }
        // TODO(#414): 완료 상태가 생기면 이미 취소가 끝난 개수를 빼고 비교한다. 지금은 완료가 없어 뺄 값이 항상 0이다
        if (command.quantity() > orderItem.quantity()) {
            throw new RestApiException(RefundErrorCode.REFUND_QUANTITY_EXCEEDED);
        }
        // 단가·판매자·옵션·이름은 구매자 입력이 아니라 주문에서 받은 값을 쓴다
        Refund refund = Refund.request(orderItem.orderId(), orderItem.orderItemId(), command.quantity(), command.buyerId(),
                orderItem.sellerId(), orderItem.productOptionId(), orderItem.productName(), orderItem.optionName(),
                orderItem.unitPrice(), command.cancelReason());
        Refund saved = saveDetectingActiveRequest(refund);
        // 배송비는 승인 때 정하지만, 지금 승인해도 받을 수 없는 요청은 판매자에게 보내지 않고 미리 막는다.
        // 저장 뒤에 판단하는 이유: 같은 상품에 처리 중 요청이 있으면 그 개수까지 더해져 판단이 "개수 초과"(500)로 깨지므로,
        // UNIQUE가 먼저 409로 막게 한다. 여기서 던지면 트랜잭션이 되돌려져 저장한 행도 남지 않는다
        RefundShippingFee fee = decideShippingFee(saved, orderItem);
        if (!fee.allowed()) {
            throw new RestApiException(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED);
        }
        return RefundResult.from(saved);
    }

    /**
     * 구매자 본인의 환불 요청 목록을 최신순으로 25건씩 조회한다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    public Page<RefundResult> getBuyerRefunds(Long buyerId, int page) {
        return refundRepository.findAllByBuyerId(buyerId, pageOf(page)).map(RefundResult::from);
    }

    /**
     * 판매자에게 온 환불 요청 목록을 최신순으로 25건씩 조회한다.
     * 상태는 컨트롤러가 도메인 enum을 쓰지 않으려고 이름으로 받는다. 허용 값은 요청 DTO가 먼저 거른다.
     *
     * @param status 상태 이름. {@code null}이면 전체
     * @param page   0부터 시작하는 페이지 번호
     */
    public Page<RefundResult> getSellerRefunds(Long sellerId, String status, int page) {
        if (status == null) {
            return refundRepository.findAllBySellerId(sellerId, pageOf(page)).map(RefundResult::from);
        }
        return refundRepository.findAllBySellerIdAndStatus(sellerId, RefundStatus.valueOf(status), pageOf(page))
                .map(RefundResult::from);
    }

    /**
     * 판매자 본인 상품의 환불 요청 한 건을 조회한다. 승인 전에 주문을 조회할 주문 ID를 얻으려고 쓴다.
     * 잠그지 않는다. 주문 호출 동안 행을 잠가 두지 않으려는 것이고, 상태는 {@link #approve}가 잠근 뒤 다시 확인한다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}
     */
    public RefundResult getSellerRefund(Long refundId, Long sellerId) {
        return refundRepository.findById(refundId)
                .filter(found -> found.isOwnedBySeller(sellerId))
                .map(RefundResult::from)
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
    }

    /**
     * 판매자가 승인한다. 같은 묶음의 처리 중 요청을 모아 배송비를 판단하고, 받을 수 있으면 "처리 중"으로 바꾸며 배송비를 저장한다.
     * 받을 수 없으면(취소 금액이 뺄 배송비보다 작음) 사유를 넣어 자동 거절한다. 요청됨으로 남기면 구매자가 이유를 모르고 요청도 쌓이기 때문이다.
     *
     * <p>같은 판매자 묶음의 승인을 한 줄로 세운다(#411). 묶음에서 진행 중(요청됨·처리 중)인 환불과 이번 환불의 ID를 먼저 읽고,
     * ID 오름차순으로 한 건씩 잠근 뒤 집계한다. 끝난 행(거절·철회)은 다시 처리 중이 될 수 없어 잠그지 않는다.
     * 이번 환불 행만 잠그면 같은 묶음의 다른 환불을 동시에 승인할 때 서로를 "아직 요청됨"으로 읽어,
     * 아무도 배송비를 못 받거나 기본 배송비를 두 번 뺀다. 묶음의 진행 중 행을 함께 잠그면 뒤에 온 승인은 앞 승인이 커밋할 때까지 기다린다.
     * 구매자 철회와 겹쳐도 같은 행 잠금이라 먼저 잠근 쪽만 성공한다.
     *
     * <p>READ COMMITTED로 실행한다. 기본(REPEATABLE READ)은 트랜잭션의 첫 조회(잠글 ID 읽기) 때 모습을 끝까지 보여 주므로,
     * 잠금을 기다리는 동안 앞 승인이 저장한 "처리 중"이 집계에 보이지 않아 줄을 세워도 배송비가 틀린다.
     * 반드시 이 메서드가 트랜잭션을 새로 시작해야 하므로 트랜잭션 밖(Facade)에서만 부른다.
     *
     * @param orderItem 승인 직전에 주문에서 다시 받은 주문 상품 정보. 배송비 값만 쓴다(출고 확인은 #412).
     *                  묶음을 이 값의 주문 ID로 찾으므로 환불의 주문 ID로 조회한 값이어야 한다. 다른 주문 값이면 묶음에 이 환불이 없어
     *                  {@code REFUND_NOT_FOUND}가 된다(Facade가 환불의 주문 ID로 조회한다)
     * @throws RestApiException      없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     * @throws IllegalStateException 주문 정보가 다른 주문 상품이면, 또는 이미 열린 트랜잭션 안에서 부르면 (부르는 쪽 버그)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundResult approve(Long refundId, Long sellerId, RefundableOrderItem orderItem) {
        requireNewTransaction();
        Refund refund = lockBundleAndFind(refundId, sellerId, orderItem.orderId());
        // 엔티티의 approve도 상태를 보지만, 배송비 판단보다 먼저 막아야 한다. 이미 처리 중인 요청은 자기 개수가 집계에 들어가
        // 판단이 "개수 초과"(500)로 깨지므로 그 전에 409로 돌려준다
        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
        if (!refund.getOrderItemId().equals(orderItem.orderItemId())) {
            throw new IllegalStateException("환불과 다른 주문 상품 정보로 승인할 수 없습니다. refundId=" + refundId);
        }
        // 집계는 이 환불을 처리 중으로 바꾸기 전에 해야 한다. 바꾼 뒤에 조회하면 자동 flush로 이번 환불이 집계에 들어가고,
        // 정책이 이번 개수를 또 더해 "개수 초과"(500)가 난다
        RefundShippingFee fee = decideShippingFee(refund, orderItem);
        LocalDateTime now = LocalDateTime.now(clock);
        if (fee.allowed()) {
            refund.approve(now, fee);
        } else {
            // 판매자 거절과 같은 상태로 남긴다. 상태를 따로 두지 않는 이유: 구매자에게는 "거절, 사유"로 충분하고 다시 요청할 수 있다
            refund.reject(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED.getMessage(), now);
        }
        return RefundResult.from(refund);
    }

    /**
     * 판매자가 거절한다. 거절은 쓸 주문 값이 없어 주문을 부르지 않는다. 주문이 느리거나 장애여도 거절은 막히지 않는다.
     * 승인과 같은 이유로 행을 잠근다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 사유가 비었으면 {@code REJECT_REASON_REQUIRED},
     *                          요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    @Transactional
    public RefundResult reject(Long refundId, Long sellerId, String rejectReason) {
        Refund refund = findSellerRefundForUpdate(refundId, sellerId);
        refund.reject(rejectReason, LocalDateTime.now(clock));
        return RefundResult.from(refund);
    }

    /**
     * 구매자가 본인 요청을 승인 전에 거둔다. 판매자 결정과 겹칠 때를 위해 {@link #approve}와 같은 방식으로 행을 잠근다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    @Transactional
    public RefundResult withdraw(Long refundId, Long buyerId) {
        Refund refund = refundRepository.findByIdForUpdate(refundId)
                .filter(found -> found.isOwnedByBuyer(buyerId))
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
        refund.withdraw(LocalDateTime.now(clock));
        return RefundResult.from(refund);
    }

    // 승인은 이 메서드가 트랜잭션을 새로 시작했을 때만 진행한다. 바깥 트랜잭션에 합류하면 두 가지가 조용히 깨진다.
    // 1) 스프링이 READ COMMITTED 지정을 경고 없이 무시한다(바깥이 기본 격리 수준이면 잠금 뒤에도 옛 값을 읽음)
    // 2) 바깥이 READ COMMITTED여도, 바깥에서 같은 환불을 먼저 읽어 뒀다면 잠금 조회가 그 옛 엔티티를 돌려줘 잠그기 전 상태로 판단한다
    // 격리 수준만 검사하면 2)를 놓쳐서 "새로 시작했는지"를 본다. 새로 시작했으면 격리 수준도 적용되고 영속성 컨텍스트도 새것이다.
    // 단, "영속성 컨텍스트도 새것"은 OSIV(open-in-view)가 꺼져 있다는 전제다. 켜면 Facade가 앞서 읽은 환불이 승인 트랜잭션까지 남아
    // 잠금 조회가 그 옛 객체를 돌려주고, 이 검사로는 막지 못한다(지금은 refund·monolith 설정 모두 open-in-view: false, 통합 테스트로 확인).
    // 같은 클래스 안에서 this.approve()로 부르면 프록시를 거치지 않아 @Transactional도 이 검사의 판단도 부르는 메서드 기준이 된다.
    // 그런 호출은 service.md가 금지하므로 이 검사로 따로 막지 않는다
    // 트랜잭션이 아예 없을 때(스프링 없이 부르는 단위 테스트)는 검사하지 않는다. 운영에서 트랜잭션 없이 불리면 잠금 쿼리가 바로 예외를 낸다
    private static void requireNewTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionAspectSupport.currentTransactionStatus().isNewTransaction()) {
            throw new IllegalStateException("환불 승인은 이미 열린 트랜잭션 안에서 부를 수 없습니다. 새 트랜잭션으로 실행해야 합니다.");
        }
    }

    // 묶음(주문 + 판매자)의 진행 중 환불과 이번 환불을 ID 오름차순으로 한 건씩 잠그고 그중 이번 환불을 돌려준다.
    // 판매자 ID로 묶음을 찾으므로 남의 요청이나 없는 요청은 목록에 들어 있지 않아 없는 것과 같게 막는다
    private Refund lockBundleAndFind(Long refundId, Long sellerId, Long orderId) {
        List<Long> lockTargetIds = refundRepository.findLockTargetIds(orderId, sellerId, refundId, LOCK_TARGET_STATUSES);
        if (!lockTargetIds.contains(refundId)) {
            throw new RestApiException(RefundErrorCode.REFUND_NOT_FOUND);
        }
        // 한 번의 IN 조회로 잠그면 잠금 순서가 DB 실행 계획에 달려 보장되지 않는다. 한 건씩 ID 오름차순으로 잠가야
        // 승인끼리 항상 같은 순서로 기다려 서로 반대 순서로 기다리는 교착 상태가 생기지 않는다. 묶음 행은 많지 않아 쿼리 수는 문제되지 않는다
        // 잠금을 기다리다 DB 잠금 대기 시간(innodb_lock_wait_timeout)을 넘기면 예외를 그대로 던져 500으로 드러낸다.
        // 요청 저장(#409)과 같은 기준이다: 승인 트랜잭션에는 외부 호출이 없어 정상이면 잠금을 짧게 쥐므로, 오래 기다렸다면
        // 사용자 잘못이 아니라 DB나 다른 트랜잭션의 이상이다. 저장된 것이 없어 환불은 요청됨 그대로 남고 다시 승인하면 된다
        // 환불 행은 지우지 않아 ID를 읽은 뒤 잠그는 사이에 사라질 일은 없다. 그래도 사라지면 없는 요청으로 막는다.
        // 위에서 목록에 이번 환불이 있는 것을 확인했으므로 반복이 끝나면 target은 항상 채워져 있다
        Refund target = null;
        for (Long id : lockTargetIds) {
            Refund locked = refundRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
            if (id.equals(refundId)) {
                target = locked;
            }
        }
        return target;
    }

    // 판매자 결정은 트랜잭션의 첫 조회로 잠근다(findByIdForUpdate 설명 참고). 남의 요청은 있는지도 알려 주지 않으려고 없는 것과 같게 막는다
    private Refund findSellerRefundForUpdate(Long refundId, Long sellerId) {
        return refundRepository.findByIdForUpdate(refundId)
                .filter(found -> found.isOwnedBySeller(sellerId))
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
    }

    // 같은 묶음(주문 + 판매자)에서 처리 중인 요청만 센다. 요청됨은 아직 승인될지 모르고, 거절·철회는 취소되지 않는다.
    // 판단할 환불은 요청됨이라 집계에 들어가지 않는다
    private RefundShippingFee decideShippingFee(Refund refund, RefundableOrderItem orderItem) {
        List<CancelledQuantity> cancelled = refundRepository.sumCancelledByOrderItem(
                refund.getOrderId(), refund.getSellerId(), CANCELLING_STATUSES);
        return RefundShippingFeePolicy.decide(refund, orderItem.bundle(), cancelled);
    }

    private static Pageable pageOf(int page) {
        return PageRequest.of(page, PAGE_SIZE, LATEST_FIRST);
    }

    // saveAndFlush로 INSERT를 바로 보내야 UNIQUE 위반이 이 메서드 안에서 나서 에러 코드로 바꿀 수 있다.
    // save만 쓰면 커밋 때 터져 500이 된다
    private Refund saveDetectingActiveRequest(Refund refund) {
        try {
            return refundRepository.saveAndFlush(refund);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT)) {
                throw new RestApiException(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            // 철회·거절이 진행 중 칸을 비우고 커밋하는 순간 같은 상품의 새 요청이 둘 이상 오면, 두 INSERT가 비워지는 UNIQUE 항목의
            // 잠금을 함께 얻은 뒤 서로를 기다려 MySQL이 한쪽을 교착 상태로 되돌린다(직접 재현함). 되돌려진 쪽은 다른 요청이
            // 먼저 들어간 경우라 중복과 같게 409로 돌려준다.
            // 같은 예외에 잠금 대기 시간 초과도 들어오는데, 그건 DB가 느린 것이지 중복이 아니라 그대로 던져 500으로 드러낸다
            if (isDeadlock(e)) {
                throw new RestApiException(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS);
            }
            throw e;
        }
    }

    // 교착 상태는 SQLState 40001(MySQL 1213), 잠금 대기 시간 초과는 HY000(MySQL 1205)이라 SQLState로 가른다.
    // 스프링은 둘 다 CannotAcquireLockException으로 바꾸므로 예외 종류로는 가를 수 없다(재현에서 확인)
    private static boolean isDeadlock(PessimisticLockingFailureException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && DEADLOCK_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
