package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundDecisionCommand;
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
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;

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
     *                          진행 중인 요청이 있으면 {@code REFUND_ALREADY_IN_PROGRESS}
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
        return RefundResult.from(saveDetectingActiveRequest(refund));
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
     * 판매자가 본인 상품의 요청을 승인하거나 거절한다.
     * 승인은 "처리 중"으로 바꾸기만 하고 다른 모듈을 부르지 않는다. 이미 출고된 상품은 배송 잠금(#412)이 막는다.
     *
     * <p>행을 잠근 뒤 상태를 확인한다. 구매자 철회와 동시에 오면 먼저 잠근 쪽만 성공하고, 뒤에 온 쪽은 바뀐 상태를 보고 막힌다.
     * 잠그지 않으면 둘 다 "요청됨"을 읽고 둘 다 저장해, 나중에 저장한 쪽이 앞의 결과를 덮어쓴다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE},
     *                          거절인데 사유가 비었으면 {@code REJECT_REASON_REQUIRED}
     */
    @Transactional
    public RefundResult decide(RefundDecisionCommand command) {
        Refund refund = refundRepository.findByIdForUpdate(command.refundId())
                .filter(found -> found.isOwnedBySeller(command.sellerId()))
                .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        switch (command.decision()) {
            case APPROVE -> refund.approve(now);
            case REJECT -> refund.reject(command.rejectReason(), now);
        }
        return RefundResult.from(refund);
    }

    /**
     * 구매자가 본인 요청을 승인 전에 거둔다. 판매자 결정과 겹칠 때를 위해 {@link #decide}와 같은 방식으로 행을 잠근다.
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
