package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 요청의 DB 작업을 맡는다. 주문 모듈 호출은 {@link RefundFacade}가 하고, 이 서비스는 짧은 트랜잭션으로 확인과 저장만 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundService {

    // 마이그레이션의 제약 이름과 같아야 한다. 이 이름으로 "진행 중 요청 중복"과 다른 무결성 오류를 구분한다
    private static final String ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT = "uk_refund_active_order_item_id";
    // 배송 목록과 같은 페이지 크기. 클라이언트가 바꿀 수 없다
    private static final int PAGE_SIZE = 25;
    // 최신순. 같은 시각이면 나중에 만든 요청이 먼저 오도록 ID를 함께 쓴다(페이지 사이에 순서가 흔들리지 않게)
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final RefundRepository refundRepository;

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
        }
    }
}
