package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.refund.service.dto.ShipmentReserveResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 환불 요청과 판매자 승인처럼 다른 모듈(주문·배송·상품·지갑) 호출이 섞인 흐름을 조율한다.
 * 외부 호출 동안 DB 트랜잭션과 행 잠금을 잡지 않도록 이 클래스에는 트랜잭션을 두지 않고, DB 저장은 {@link RefundService}의 짧은 트랜잭션으로 나눈다.
 *
 * <p>승인 뒤에는 되돌리지 않는다(보상 처리 없음). {@code service.md}의 "실패하면 보상 처리"와 다른 예외다:
 * 판매자가 승인한 취소를 무르면 상품이 다시 나갈 수 있고, 예약 풀기 API도 두지 않기로 했다(설계 3장).
 * 대신 실패한 단계를 기록하고 "처리 중"으로 남겨, 같은 환불ID로 다시 보내면 받는 쪽이 중복을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundFacade {

    private final RefundService refundService;
    private final RefundOrderClient orderClient;
    private final RefundDeliveryClient deliveryClient;
    private final RefundProductClient productClient;
    private final RefundWalletClient walletClient;

    /**
     * 구매자의 취소 요청을 받는다(흐름 1~2번). 주문에서 정보를 받아 확인한 뒤 "요청됨"으로 저장한다.
     *
     * @throws RestApiException 주문 상품이 없거나 남의 것이면 {@code ORDER_ITEM_NOT_FOUND}, 그 밖은 {@link RefundService#request}를 따른다
     */
    public RefundResult request(RefundRequestCommand command) {
        RefundableOrderItem orderItem = orderClient.getOrderItem(command.orderId(), command.orderItemId());
        return refundService.request(command, orderItem);
    }

    /**
     * 판매자의 승인·거절을 처리한다. 승인 불가도 상태가 저장되는 정상 결과라 결과로 돌려준다.
     *
     * @throws RestApiException 거절 규칙은 {@link RefundService#reject}, 승인 규칙은 {@link #approve}를 따른다
     */
    public RefundResult decide(RefundDecisionCommand command) {
        if (command.decision() == RefundDecision.REJECT) {
            return refundService.reject(command.refundId(), command.sellerId(), command.rejectReason());
        }
        return approve(command.refundId(), command.sellerId());
    }

    /**
     * 판매자 승인을 처리한다(설계 4.4).
     * 0. 배송 건 확인 → 주문 조회 → 1~3. 줄 세우기·처리 중·금액 저장 → 4. 배송 잠금 → 5. 지갑·주문·상품·잠금 해제 → 6. 완료.
     * 배송 잠금이 맨 처음이고 잠금 해제가 맨 마지막이다. 잠긴 동안에는 출고·구매확정·정산이 일어나지 않아 중간 단계가 안전하다.
     * 배송 건이 없으면 잠금과 잠금 해제를 건너뛰고 나머지 단계만 한다.
     *
     * @throws RestApiException 다른 모듈 호출 실패면 {@code *_API_FAILED}(처리 중으로 남고 실패 단계 기록),
     *                          그 밖은 {@link RefundService#getForDecision}, {@link RefundService#startApproval}을 따른다
     */
    private RefundResult approve(Long refundId, Long sellerId) {
        RefundResult requested = refundService.getForDecision(refundId, sellerId);

        // "처리 중"으로 바꾸기 전에 확인한다. 배송 잠금은 배송 건이 없으면 404라, 처리 중으로 바꾼 뒤 알게 되면 멈춘 채 남는다.
        // 배송 건이 없으면 송장을 등록할 곳이 없어 처리 중에 출고·구매확정·정산이 끼어들 수 없으므로 잠그지 않고 진행한다.
        // 송장 없이 상자를 이미 보냈는지는 시스템이 알 수 없고, 그 상태로 승인한 것은 판매자 책임이다.
        // TODO(논의 필요): 배송 건이 나중에 생기면(결제 완료 이벤트 재처리) 결제 때 수량으로 만들어져 취소한 상품까지 나갈 수 있고,
        //                  확인 뒤 처리 중에 생기면 잠금 없이 송장을 등록할 수 있다. 배송 건을 만들 때 확정된 취소 수량을 빼도록 배송팀과 협의 필요
        boolean hasShipment = deliveryClient.hasShipment(requested.orderItemId());
        // 묶음 수량과 배송비는 결제 뒤 바뀌지 않으므로 줄 서기 전에 읽어도 된다. 줄 선 채로 응답을 기다리면 같은 묶음이 모두 멈춘다
        RefundableOrderItem orderItem = orderClient.getOrderItem(requested.orderId(), requested.orderItemId());
        RefundResult processing = refundService.startApproval(refundId, sellerId, orderItem);

        if (hasShipment) {
            ShipmentReserveResult reserved = runStep(refundId, RefundFailedStep.SHIPMENT_RESERVE,
                    () -> deliveryClient.reserveCancel(refundId, processing.orderItemId(), processing.quantity()));
            if (reserved == ShipmentReserveResult.REJECTED) {
                return saveFinalStatus(refundId, "승인 불가", () -> refundService.markNotApprovable(refundId));
            }
        }

        // 다시 처리할 때도 같은 금액을 보내야 지갑의 환불ID 중복 확인과 맞으므로, 승인 때 저장한 금액을 그대로 보낸다.
        // 0원이면 지갑을 부르지 않는다. 취소 금액이 뺀 배송비와 같으면 0원 환불이 되는데(구매자는 취소한 상품 금액으로 배송비를 내는 셈),
        // 돌려줄 돈이 없고 지갑은 0원을 422로 거절하기로 약속했다(요청서 #327). 0원 취소 기록은 환불 테이블에 남는다
        if (processing.refundAmount() > 0) {
            runStep(refundId, RefundFailedStep.WALLET, () -> {
                walletClient.refund(refundId, processing.orderId(), processing.buyerId(), processing.sellerId(), processing.refundAmount());
                return null;
            });
        }
        // 뺀 배송비도 승인 때 저장한 값을 보낸다. 주문이 같은 환불ID로 다시 받아도 값이 같아야 중복 확인과 맞는다
        runStep(refundId, RefundFailedStep.ORDER, () -> {
            orderClient.applyRefund(refundId, processing.orderItemId(), processing.quantity(), processing.deductedShippingFeeAmount());
            return null;
        });
        runStep(refundId, RefundFailedStep.PRODUCT, () -> {
            productClient.releaseStock(refundId, processing.orderId(), processing.productOptionId(), processing.quantity());
            return null;
        });
        // 잠그지 않았으면 풀 것도 없다
        if (hasShipment) {
            runStep(refundId, RefundFailedStep.SHIPMENT_CONFIRM, () -> {
                deliveryClient.confirmCancel(refundId);
                return null;
            });
        }
        return saveFinalStatus(refundId, "완료", () -> refundService.complete(refundId));
    }

    // 다른 모듈 호출은 끝났는데 상태 저장만 실패하면(DB 순간 장애 등) 처리 중에 실패 단계 없이 남아 진행 중인 건과 구분되지 않는다.
    // 같은 원인이면 실패 기록도 실패하기 쉬우므로 운영에서 찾을 수 있게 refundId를 WARN으로 남기고 다시 던진다.
    // 예외 내용은 ApiControllerAdvice가 ERROR로 한 번 남기므로 여기서는 빼서 같은 스택트레이스가 두 번 찍히지 않게 한다
    private RefundResult saveFinalStatus(Long refundId, String finalStatus, Supplier<RefundResult> save) {
        try {
            return save.get();
        } catch (RuntimeException e) {
            log.warn("다른 모듈 처리 후 환불 상태 저장 실패. refundId={}, status={}", refundId, finalStatus);
            throw e;
        }
    }

    // 실패하면 멈춘 단계를 기록하고 원래 예외를 그대로 던진다. 1차 구현에는 자동 재시도가 없다(설계 11장)
    private <T> T runStep(Long refundId, RefundFailedStep step, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            recordFailureQuietly(refundId, step, e);
            throw e;
        }
    }

    // 기록 자체가 실패해도(DB 순간 장애 등) 원래 예외를 가리지 않게 로그만 남긴다. 기록이 없으면 진행 중인 건과 구분되지 않으니 WARN으로 남긴다
    private void recordFailureQuietly(Long refundId, RefundFailedStep step, RuntimeException cause) {
        try {
            refundService.recordFailure(refundId, step, failureCodeOf(cause));
        } catch (RuntimeException e) {
            log.warn("환불 실패 단계 기록 실패. refundId={}, step={}, cause={}", refundId, step, failureCodeOf(cause), e);
        }
    }

    // 내부 메시지를 DB에 남기지 않도록 에러 코드 이름이나 예외 클래스 이름만 쓴다
    private static String failureCodeOf(RuntimeException e) {
        if (e instanceof RestApiException restApiException) {
            return restApiException.getErrorCode().name();
        }
        return e.getClass().getSimpleName();
    }
}
