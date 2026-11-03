package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundShippingFeePolicy;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.refund.service.dto.SellerGroupItem;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 환불 요청의 DB 작업을 맡는다. 다른 모듈 호출은 {@link RefundFacade}가 하고, 이 서비스는 짧은 트랜잭션으로 저장만 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundService {

    private static final String ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT = "uk_refund_active_order_item_id";
    // 배송 목록과 같은 페이지 크기. 클라이언트가 바꿀 수 없다
    private static final int PAGE_SIZE = 25;
    // 최신순. 같은 시각이면 나중에 만든 요청이 먼저 오도록 ID를 함께 쓴다
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    // 묶음 전부 취소 판단에 넣는 상태. 기준은 RefundStatus 한 곳에 두고 여기서 모은다
    private static final List<RefundStatus> CANCELLED_STATUSES = cancelledStatuses();

    private final RefundRepository refundRepository;
    private final Clock clock;

    /**
     * 구매자의 취소 요청을 "요청됨"으로 저장한다. 주문·배송·지갑·상품은 이 단계에서 아무것도 바뀌지 않는다.
     * 남은 개수는 주문에게 받은 산 개수에서 환불 테이블의 처리 중 + 완료 개수를 빼서 센다(취소 개수의 원래 출처가 환불이라서).
     * 진행 중인 요청은 {@code active_order_item_id} UNIQUE가 막는다.
     * 일부 취소로 무료배송 기준이 깨지는데 취소 금액이 배송비보다 작으면 여기서 먼저 막는다. 잠그지 않은 1차 확인이고,
     * 같은 묶음의 다른 요청이 먼저 끝나면 판단이 달라지므로 승인할 때({@link #startApproval}) 다시 확인한다.
     *
     * @param orderItem 주문 모듈에서 받은 주문 상품 정보
     * @throws RestApiException 남의 주문이면 {@code ORDER_ITEM_NOT_FOUND}, 출고 전이 아니면 {@code ORDER_ITEM_NOT_CANCELLABLE},
     *                          남은 개수보다 많으면 {@code REFUND_QUANTITY_EXCEEDED}, 진행 중인 요청이 있으면 {@code REFUND_ALREADY_IN_PROGRESS},
     *                          개수가 1 미만이면 {@code INVALID_REFUND_QUANTITY},
     *                          취소 금액이 빼야 할 배송비보다 작으면 {@code PARTIAL_CANCEL_NOT_ALLOWED}
     */
    @Transactional
    public RefundResult request(RefundRequestCommand command, RefundableOrderItem orderItem) {
        // 남의 주문은 존재 여부를 숨기려고 없는 것과 같게 응답한다
        if (!orderItem.buyerId().equals(command.buyerId())) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        if (!orderItem.isCancellable()) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_CANCELLABLE);
        }
        // 처리 중도 뺀다. 앞 요청이 완료로 바뀌는 순간과 겹치면, 완료만 세는 경우 아직 0으로 보여 통과하고
        // 저장 때는 앞 요청이 끝나 진행 중 UNIQUE에도 안 걸려 승인할 수 없는 요청이 남는다. 승인 때 배송비 판단과도 기준이 같다
        long cancelled = refundRepository.sumQuantityByOrderItemIdAndStatusIn(orderItem.orderItemId(), CANCELLED_STATUSES);
        if (command.quantity() > orderItem.quantity() - cancelled) {
            throw new RestApiException(RefundErrorCode.REFUND_QUANTITY_EXCEEDED);
        }
        Refund refund = Refund.request(orderItem.orderId(), orderItem.orderItemId(), command.quantity(), command.buyerId(),
                orderItem.sellerId(), orderItem.productOptionId(), orderItem.productName(), orderItem.optionName(),
                orderItem.unitPrice(), command.cancelReason());
        // 결과는 버린다. 금액은 승인할 때 잠근 뒤 다시 계산해 저장하고, 여기서는 막을 요청만 거른다
        List<CancelledQuantity> bundleCancelled =
                refundRepository.sumQuantityByOrderItem(orderItem.orderId(), orderItem.sellerId(), CANCELLED_STATUSES);
        RefundShippingFeePolicy.decide(refund, toBundleShipping(orderItem), bundleCancelled, 0L);
        return RefundResult.from(saveDetectingActiveRequest(refund));
    }

    /**
     * 구매자가 승인 전에 요청을 거둔다. 승인과 동시에 오면 행 잠금으로 먼저 처리된 쪽만 성공한다.
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

    /**
     * 판매자가 거절한다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 사유가 비었으면 {@code REJECT_REASON_REQUIRED},
     *                          요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    @Transactional
    public RefundResult reject(Long refundId, Long sellerId, String reason) {
        Refund refund = findForSellerForUpdate(refundId, sellerId);
        refund.reject(reason, LocalDateTime.now(clock));
        return RefundResult.from(refund);
    }

    /**
     * 승인 전에 판매자 본인 요청인지, 아직 요청됨인지 확인한다.
     * 다른 모듈을 부르기 전에 걸러, 남의 요청이나 끝난 요청으로 배송·주문 API를 부르지 않게 한다.
     * 상태는 승인 트랜잭션({@link #startApproval})에서 잠근 뒤 다시 확인한다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    public RefundResult getForDecision(Long refundId, Long sellerId) {
        Refund refund = refundRepository.findById(refundId)
                .filter(found -> found.isOwnedBySeller(sellerId))
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
        return RefundResult.from(refund);
    }

    /**
     * 판매자 승인으로 "처리 중"으로 바꾸고 돌려줄 배송비·추가 운임과 뺄 배송비를 저장한다(설계 4.4의 1~3번).
     *
     * <p>같은 판매자 묶음의 승인을 한 줄로 세운다: 묶음의 환불 ID만 먼저 읽고(엔티티 아님) ID 순서로 PK를 잠근다.
     * 잠근 뒤 상품별 처리 중·완료 개수를 다시 집계해, ID를 읽은 뒤 끼어든 다른 승인의 커밋까지 본다.
     * 그래서 묶음을 전부 취소하는 요청이 정확히 하나만 배송비를 받는다.
     *
     * <p>READ COMMITTED로 실행한다. 기본(REPEATABLE READ)은 트랜잭션의 첫 조회 때 모습을 끝까지 보여 주므로,
     * 잠금을 기다리는 동안 앞 승인이 저장한 "처리 중"이 집계에 보이지 않아 배송비가 빠진다.
     * 이미 열린 트랜잭션 안에서 부르면 격리 수준이 적용되지 않으므로 트랜잭션 밖(Facade)에서만 부른다.
     *
     * @param orderItem 승인 직전에 주문 모듈에서 받은 정보 (묶음 상품별 주문 수량, 배송비, 추가 운임)
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE},
     *                          이번까지 더해 주문 수량을 넘으면 {@code REFUND_QUANTITY_EXCEEDED},
     *                          요청 뒤 같은 묶음의 다른 취소가 먼저 끝나 이번 취소 금액이 빼야 할 배송비보다 작아졌으면
     *                          {@code PARTIAL_CANCEL_NOT_ALLOWED}(요청됨 그대로 남아 판매자가 거절한다)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundResult startApproval(Long refundId, Long sellerId, RefundableOrderItem orderItem) {
        // 바깥 트랜잭션에 합류하면 스프링은 격리 수준 지정을 경고 없이 무시한다. 그러면 테스트는 통과해도 배송비가 조용히 빠지므로 바로 실패시킨다
        if (!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("환불 승인은 READ COMMITTED 트랜잭션에서만 실행해야 합니다.");
        }
        List<Long> bundleIds = refundRepository.findIdsByOrderIdAndSellerId(orderItem.orderId(), sellerId);
        // 다른 판매자나 다른 주문의 요청이면 묶음 ID가 비어 있을 수 있다. 빈 IN 조건으로 잠금 쿼리를 보내지 않는다
        if (bundleIds.isEmpty()) {
            throw new RestApiException(RefundErrorCode.REFUND_NOT_FOUND);
        }
        Refund target = null;
        for (Refund locked : refundRepository.findAllByIdInForUpdate(bundleIds)) {
            if (locked.getId().equals(refundId)) {
                target = locked;
            }
        }
        // 다른 판매자나 다른 주문의 요청이면 묶음 ID에 들어 있지 않다
        if (target == null || !target.getOrderItemId().equals(orderItem.orderItemId())) {
            throw new RestApiException(RefundErrorCode.REFUND_NOT_FOUND);
        }
        // 잠그기 전에 철회·거절·다른 승인이 먼저 끝났을 수 있다. 묶음 판단 전에 걸러야 끝난 요청으로 개수 초과를 잘못 내지 않는다
        if (target.getStatus() != RefundStatus.REQUESTED) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
        // 순서가 중요하다: 이번 요청이 아직 요청됨일 때 집계해야 집계에서 빠지고, 정책이 한 번만 더한다.
        // startProcessing을 집계 앞으로 옮기면 조회 전 자동 flush로 처리 중이 반영되어 이번 요청을 두 번 세고 배송비를 아무도 못 받는다
        List<CancelledQuantity> cancelled = refundRepository.sumQuantityByOrderItem(orderItem.orderId(), sellerId, CANCELLED_STATUSES);
        long previouslyDeducted = refundRepository.sumDeductedShippingFee(orderItem.orderId(), sellerId, CANCELLED_STATUSES);
        RefundShippingFee fee = RefundShippingFeePolicy.decide(target, toBundleShipping(orderItem), cancelled, previouslyDeducted);
        target.startProcessing(fee, LocalDateTime.now(clock));
        return RefundResult.from(target);
    }

    /**
     * 배송 잠금이 거절되어 "승인 불가"로 끝낸다.
     *
     * @throws RestApiException 처리 중이 아니면 {@code INVALID_REFUND_STATE}
     */
    @Transactional
    public RefundResult markNotApprovable(Long refundId) {
        Refund refund = findForUpdate(refundId);
        refund.markNotApprovable(LocalDateTime.now(clock));
        return RefundResult.from(refund);
    }

    /**
     * 처리 중에 멈춘 단계와 오류 코드를 기록한다. 상태는 처리 중 그대로다.
     *
     * @throws RestApiException 처리 중이 아니면 {@code INVALID_REFUND_STATE}
     */
    @Transactional
    public void recordFailure(Long refundId, RefundFailedStep step, String failureCode) {
        findForUpdate(refundId).recordFailure(step, failureCode);
    }

    /**
     * 모든 단계가 끝나 "완료"로 바꾼다.
     *
     * @throws RestApiException 처리 중이 아니면 {@code INVALID_REFUND_STATE}
     */
    @Transactional
    public RefundResult complete(Long refundId) {
        Refund refund = findForUpdate(refundId);
        refund.complete(LocalDateTime.now(clock));
        return RefundResult.from(refund);
    }

    /**
     * 구매자의 환불 요청 목록을 최신순으로 25건씩 조회한다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    public Page<RefundResult> getBuyerRefunds(Long buyerId, int page) {
        return refundRepository.findAllByBuyerId(buyerId, pageOf(page)).map(RefundResult::from);
    }

    /**
     * 판매자에게 온 환불 요청 목록을 최신순으로 25건씩 조회한다.
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

    // 진행 중인 요청은 사전 조회 없이 UNIQUE 제약 하나로 막는다. 사전 조회해도 동시 요청은 제약이 막아야 하므로 확인 경로를 하나로 둔다.
    // 커밋 때 터지면 서비스 밖이라 409로 바꿀 수 없어 여기서 flush한다. 다른 제약 위반은 그대로 던진다
    private Refund saveDetectingActiveRequest(Refund refund) {
        try {
            return refundRepository.saveAndFlush(refund);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT)) {
                throw new RestApiException(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS);
            }
            throw e;
        }
    }

    private Refund findForSellerForUpdate(Long refundId, Long sellerId) {
        // 다른 판매자의 요청은 존재 여부를 숨기려고 없는 것과 같게 응답한다
        return refundRepository.findByIdForUpdate(refundId)
                .filter(found -> found.isOwnedBySeller(sellerId))
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
    }

    private Refund findForUpdate(Long refundId) {
        return refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
    }

    // 주문 응답의 묶음 목록과 상품 자체 수량이 다르면 어느 쪽을 믿을지 알 수 없어 판단하지 않는다(주문 모듈 응답 버그)
    private static BundleShipping toBundleShipping(RefundableOrderItem orderItem) {
        List<BundleItem> bundleItems = new ArrayList<>();
        for (SellerGroupItem item : orderItem.sellerGroupItems()) {
            if (item.orderItemId().equals(orderItem.orderItemId()) && item.quantity() != orderItem.quantity()) {
                throw new IllegalStateException("주문 응답의 묶음 수량과 상품 수량이 다릅니다. orderItemId=" + orderItem.orderItemId());
            }
            bundleItems.add(new BundleItem(item.orderItemId(), item.unitPrice(), item.quantity()));
        }
        return new BundleShipping(bundleItems, orderItem.shippingFee(), orderItem.remoteAreaFee(),
                orderItem.baseShippingFee(), orderItem.freeShippingThreshold());
    }

    private static List<RefundStatus> cancelledStatuses() {
        List<RefundStatus> statuses = new ArrayList<>();
        for (RefundStatus status : RefundStatus.values()) {
            if (status.countsAsCancelled()) {
                statuses.add(status);
            }
        }
        return List.copyOf(statuses);
    }

    private static Pageable pageOf(int page) {
        return PageRequest.of(page, PAGE_SIZE, LATEST_FIRST);
    }
}
