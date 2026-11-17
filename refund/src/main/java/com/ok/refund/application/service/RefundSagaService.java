package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.OrderRefundRevertFailedCommand;
import com.ok.refund.application.port.in.OrderRefundRevertedCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.in.RefundSagaUseCase;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.port.in.WalletRevertCompletedCommand;
import com.ok.refund.application.port.in.WalletRevertFailedCommand;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.OrderCancellationResult;
import com.ok.refund.application.port.out.OrderRevertRequest;
import com.ok.refund.application.port.out.OrderRevertRequestPort;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.application.port.out.WalletRefundRequest;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import com.ok.refund.application.port.out.WalletRevertRequest;
import com.ok.refund.domain.OrderRevertResultPolicy;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundSagaPlan;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import com.ok.refund.domain.WalletResultPolicy;
import com.ok.refund.domain.WalletRevertResultPolicy;
import com.ok.refund.exception.RefundErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 승인 뒤 단계를 한 곳에서 진행하는 지휘자다(#575). 다음 단계를 정하고 주문·지갑·재고 포트를 부르는 곳은 이 클래스뿐이다.
 * 입구는 넷이다. 승인 서비스가 승인 구간을 커밋한 뒤 부르는 시작({@link #startAfterApproval}), 지갑 결과 메시지를 받는 완료·실패,
 * 환불 번호만으로 이어 가는 다시 진행({@link #resume}, 지휘 작업 #576이 부른다). 네 입구는 같은 실행 메서드를 함께 쓴다.
 * 시작: 주문 취소 반영(#513) → 지갑 반환 요청(#512)까지 하고, 돌려줄 돈이 있으면 지갑 요청을 보낸 데서 멈춘다. 0원이면 지갑을 거치지 않아
 * 같은 요청 안에서 재고 복구 → 완료(#414)까지 간다. 주문이 반영을 거절하면(이미 출고 등) 지갑·재고를 부르지 않고 거절로 끝낸다(#543).
 * 지갑 완료: 읽어서 거르기(읽기 구간) → 자기 행을 잠그고 지갑 줄 성공·재고 줄 시작(구간) → 재고 복구(구간 밖, 단계 실행 틀 {@link #runStep})
 * → 자기 행을 잠그고 재고 줄 성공과 완료(구간)로 나뉜다(#544).
 * 지갑 실패: 읽어서 거르기 → 자기 행을 잠그고 지갑 줄을 멈춤으로 기록(구간)이다. 거르는 판단은 단계 진행 표를 본다(#574).
 * 회수(#546): 재고가 다시 해도 같은 이유로 거절되면 재고 줄을 멈춘 구간에서 회수 줄을 시도 중으로 만들고, 커밋 뒤 바로 Kafka로 회수를 요청한다.
 * 발행이 실패하거나 답이 늦으면 알람이 다시 보낸다.
 * 회수 결과(다시 가져옴·실패)는 자기 행을 잠그고 회수 줄에 남긴다(구간 하나). 회수 실패면 환불은 처리 중으로 남고 사람이 본다.
 * 주문 되돌리기(#622): 지갑 반환 실패를 지갑 줄에 기록한 구간, 또는 회수 성공을 기록한 구간에서 되돌리기 줄을 시도 중으로 만들고,
 * 커밋 뒤 바로 Kafka로 주문에 취소 기록 되돌리기를 요청한다(회수와 같은 틀). 주문 결과가 되돌림이면 자기 행을 잠그고 되돌리기 줄 성공과
 * 환불 실패를 함께 남기고(구간 하나), 실패면 되돌리기 줄만 멈춰 사람이 보게 한다.
 * 걸러 넘기는 경우는 예외를 던지지 않는다. 던지면 메시지 오류 처리기가 같은 메시지를 다시 시도해 줄만 막힌다.
 * 지갑 결과를 잠그지 않고 먼저 읽는 이유: 재고 복구(상품 HTTP) 동안 행 잠금을 쥐지 않으려는 것이고,
 * 읽은 뒤 잠그기 전에 상태가 바뀔 수 있어 잠근 뒤 상태를 다시 확인한다.
 * 다른 모듈 호출은 모두 단계 실행 틀({@link #runStep})로 부른다. 실패하면 그 단계의 진행 줄(refund_step)에 이유를 남기고 같은 예외를 올린다(#574).
 * 입구는 모두 트랜잭션 밖에서만 부른다. 열린 트랜잭션 안에서 부르면 실패 기록이 바깥과 함께 롤백되므로 입구에서 막는다.
 */
@Slf4j
@Service
public class RefundSagaService implements RefundSagaUseCase {

    // 주문이 취소 반영을 거절(409)했을 때 주문 줄에 남기는 이유. 운영자가 표에서 거절 원인을 구분하는 값이다
    static final String ORDER_REFUSED_REASON = "ORDER_REFUSED";
    // 지갑이 알려 준 금액·주문·구매자가 환불과 다를 때 지갑 줄의 이유에 남기는 값. 지갑의 reason과 겹치지 않게 대문자 상수로 둔다
    static final String RESULT_MISMATCH = "RESULT_MISMATCH";
    // 앞 단계가 멈춰 다시 진행할 수 없는 환불에서, 남은 할 차례·만료된 시도 중 줄을 멈출 때 남기는 이유(PR #628 검수)
    static final String BLOCKED_BY_STOPPED_STEP = "BLOCKED_BY_STOPPED_STEP";
    // 지갑 실패로 주문 되돌리기를 시작한 뒤 같은 refundId의 지갑 완료가 와 되돌리기 줄을 멈출 때 남기는 이유(#622 투표). 돈이 나갔을 수 있다는 표시다
    static final String WALLET_COMPLETED_LATE = "WALLET_COMPLETED_LATE";
    // 다시 해도 결과가 같은 다른 모듈 실패(#576). 이 실패는 다시 시도하지 않고 단계를 바로 멈춘다.
    // 상품이 해제할 수 없다고 답한 경우(409 해제 가능 수량 초과·같은 refundId인데 내용이 다름, 404 재고 기록 없음)다.
    // 주문 409는 예외가 아니라 거절 결과로 오고, 지갑 발행 실패는 Kafka 쪽 일시 실패라 여기 넣지 않는다.
    // 이 목록은 "다시 시도하지 않음"만 뜻한다. 돈을 다시 가져올지는 WALLET_REVERT_TRIGGER 하나로 따로 본다(#546)
    static final Set<String> NON_RETRYABLE_FAILURES = Set.of(RefundErrorCode.PRODUCT_RELEASE_REFUSED.name(),
            RefundErrorCode.PRODUCT_STOCK_NOT_FOUND.name());
    // 돈을 다시 가져오는 재고 실패 이유(#546). 상품 409만이다. 404는 주소 설정 오류와 가를 수 없어 넣지 않는다(봇 리뷰, 사용자 결정).
    // 위 목록에 다른 코드가 더해져도 돈이 움직이지 않게 따로 둔다. 돈을 움직이는 조건이라 좁게 둔다
    static final String WALLET_REVERT_TRIGGER = RefundErrorCode.PRODUCT_RELEASE_REFUSED.name();

    // 잠근 구간에서 표시를 남겼는지와 못 남긴 이유. 이유마다 로그 수준이 달라(바뀜은 INFO, 지갑 완료 뒤 모순은 WARN) 참·거짓으로는 부족하다
    private enum MarkOutcome {
        MARKED,
        CHANGED,
        AFTER_WALLET_SUCCESS,
        // 지갑 줄이 이미 다른 이유로 멈춰 있어 처음 이유를 그대로 둔 경우
        ALREADY_STOPPED,
        // 지갑 줄이 없어 남길 곳이 없는 경우(있을 수 없는 상태, #616 결정 13)
        NO_WALLET_STEP
    }

    // 다음 단계 줄을 준비한 결과(#616 결정 14). CALL만 다른 모듈을 부른다.
    // BUSY: 누군가 시도 중이거나 결과를 기다리는 중이라 다시 보내지 않는다. ENDED: 끝 상태라 진행하지 않는다
    private enum StepPrep {
        CALL,
        BUSY,
        ENDED
    }

    // 지갑 완료 뒤 재고 단계를 시작하려 한 결과. 이유마다 로그 수준이 다르다(바뀜·진행 중은 INFO, 있을 수 없는 상태·끝 상태는 WARN)
    private enum ProductStart {
        STARTED,
        CHANGED,
        NO_WALLET_STEP,
        PRODUCT_BUSY,
        PRODUCT_ENDED,
        // 회수가 성공해 주문 되돌리기를 시작한 환불에 같은 지갑 완료가 다시 옴(지갑은 같은 refundId에 처음 결과를 다시 보낸다). 정상 중복이다
        DUPLICATE_AFTER_REVERT,
        // 지갑 실패를 받아 주문 되돌리기를 시작한 환불에 같은 refundId의 완료가 옴(지갑 약속 위반, #622). 되돌리기 줄을 멈춰 사람이 보게 한다
        WALLET_COMPLETED_LATE,
        // 위와 같은 약속 위반인데 되돌리기 줄이 이미 끝나 있어(예: 주문이 되돌리지 못해 멈춤) 줄을 고치지 않은 경우
        WALLET_COMPLETED_LATE_AFTER_REVERT_ENDED
    }

    // 주문 성공을 기록한 결과. 다음 단계를 부를지(call)와 지금 환불 상태
    private record OrderRecorded(RefundResult current, boolean call, boolean orderStepMissing) {
    }

    // 다시 진행할 단계 줄을 따로 준비한 결과(환불 번호 입구). 처리 중이 아니면 prep은 null이다
    private record Prepared(RefundResult current, StepPrep prep) {
    }

    // 잠근 구간에서 환불을 끝내려 한 결과(완료·거절). 처리 중이 아니면 쓰지 않고 지금 상태를 담는다(#616 결정 15).
    // stepMissing: 끝낼 때 함께 바꿀 단계 줄이 없었음(있을 수 없는 상태라 WARN, #616 결정 13)
    private record Closing(RefundResult result, boolean changed, boolean found, boolean stepMissing) {
    }

    // 먼저 읽은 값. 거르는 판단에만 쓰고, 기록·완료할 때는 잠근 뒤 다시 읽는다.
    // walletStep이 없으면 있을 수 없는 상태다(#616 결정 13). productStep은 지갑 완료를 반영하기 전이면 없다
    private record Snapshot(Refund refund, RefundStep productStep, RefundStep walletStep) {
    }

    // 회수 결과를 잠근 구간에서 거른 판단과, 로그에 남길 그때의 환불·회수 줄 상태(없으면 null).
    // orderRevertTarget: 회수 성공으로 주문 되돌리기 줄을 만들었으면 되돌리기 요청 값을 꺼낼 환불(잠근 구간에서 읽은 값), 아니면 null
    private record RevertRecorded(WalletRevertResultPolicy.Verdict verdict, RefundStatus refundStatus, RefundStepStatus stepStatus,
                                  Refund orderRevertTarget) {
    }

    // 지갑 반환 실패를 잠근 구간에서 기록한 결과와, 주문 되돌리기 줄을 만들었으면 되돌리기 요청 값을 꺼낼 환불(아니면 null)
    private record WalletFailureMarked(MarkOutcome outcome, Refund orderRevertTarget) {
    }

    // 주문 되돌리기 결과를 잠근 구간에서 거른 판단과, 로그에 남길 그때의 환불·되돌리기 줄 상태(없으면 null)
    // sameFailure: 되돌리기 줄이 같은 이유로 이미 멈춰 있음(같은 실패가 다시 온 정상 중복, #622 투표). 이유 비교는 도메인이 저장할 때와 같은 규칙(자르기)으로 한다
    private record OrderRevertRecorded(OrderRevertResultPolicy.Verdict verdict, RefundStatus refundStatus,
                                       RefundStepStatus stepStatus, boolean sameFailure) {
    }

    private final OrderCancellationPort orderCancellationPort;
    private final WalletRefundRequestPort walletRefundRequestPort;
    private final ProductStockReleasePort productStockReleasePort;
    private final OrderRevertRequestPort orderRevertRequestPort;
    private final RefundPersistencePort refundPersistencePort;
    private final RefundStepPersistencePort refundStepPersistencePort;
    private final Clock clock;
    // 지갑 결과를 거르기 위한 읽기용. 잠그지 않는다
    private final TransactionTemplate readOnlyTransaction;
    // 자기 행을 잠그고 바꾸는 구간. 실패 기록(recordFailure)도 이 구간으로 따로 커밋한다. 입구가 바깥 트랜잭션을 막아 두므로 항상 새로 시작한다
    private final TransactionTemplate transaction;

    public RefundSagaService(OrderCancellationPort orderCancellationPort, WalletRefundRequestPort walletRefundRequestPort,
                             ProductStockReleasePort productStockReleasePort, OrderRevertRequestPort orderRevertRequestPort,
                             RefundPersistencePort refundPersistencePort, RefundStepPersistencePort refundStepPersistencePort,
                             Clock clock, PlatformTransactionManager transactionManager) {
        this.orderCancellationPort = orderCancellationPort;
        this.walletRefundRequestPort = walletRefundRequestPort;
        this.productStockReleasePort = productStockReleasePort;
        this.orderRevertRequestPort = orderRevertRequestPort;
        this.refundPersistencePort = refundPersistencePort;
        this.refundStepPersistencePort = refundStepPersistencePort;
        this.clock = clock;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // 승인 커밋 뒤의 단계를 차례로 부른다: 주문 취소 반영(#513) → 지갑 반환 요청(#512). 돌려줄 돈이 있으면 여기서 멈추고 처리 중으로 응답한다(#544).
    // 재고 복구와 완료는 지갑 완료 메시지를 받은 뒤 completeAfterWalletRefund가 한다. 0원만 지갑을 거치지 않아 재고 복구 → 완료(#414)까지 간다.
    // 승인 버튼으로 왔어도 자동 거절(출고됨, 배송비 모자람)로 끝났을 수 있다. 처리 중이 된 환불만 다음 단계로 간다.
    // 이 확인이 없으면 거절된 환불의 상품 금액이 지갑으로 나가고 주문에도 취소가 반영된다.
    // 주문을 지갑보다 먼저 부르는 이유: 승인 사이에 출고된 상품은 주문이 반영을 거절한다. 지갑을 먼저 부르면 이미 나간 반환 요청을
    // 되돌릴 수 없어, 돈은 나갔는데 상품은 배송되는 일이 생긴다. 주문이 거절하면 지갑·재고를 부르지 않고 거절로 끝낸다.
    // 지갑 발행이 실패하면 주문은 이미 취소를 기록한 상태다. 나중에 같은 refundId로 다시 보내면 주문이 성공으로 답하므로(멱등)
    // 다시 시도(#576)가 지갑 요청부터 이어 갈 수 있다. 다음 단계 줄이 이미 있으면 상태대로 다룬다(#616 결정 14).
    // 다른 모듈에 보낼 값은 승인 결과가 아니라 환불 행에서 꺼낸다(#616 결정 18). 환불 번호만 받는 입구와 같은 출처를 쓰려는 것이다.
    // 승인 요청 안의 단계(주문·지갑·0원 재고)도 단계 실행 틀(runStep)로 부른다(#574). 실패하면 그 단계 줄이 "할 차례"와 이유로 남고,
    // 예외는 그대로 올라가 판매자 응답은 예전과 같다(500·503, 다시 승인하면 409)
    // 예전과 다른 응답이 하나 있다. 주문 반영 뒤 단계 표를 고치는 구간(recordOrderSucceeded)이 실패하면 주문은 반영됐는데 500으로 응답하고,
    // 주문 줄은 "시도 중"으로 남는다. 반대로 지갑 발행 뒤 "결과 대기"로 바꾸는 구간(markWalletAwaitingResult)이 실패해도 WARN만 남기고 처리 중으로 응답한다
    // 지갑 줄은 발행하기 전에 다른 구간에서 먼저 기록해 커밋한다(먼저 기록하고 보내기). 발행이 실패해도 주문만 취소된 채 흔적 없이 남지 않고
    // "WALLET: 할 차례"가 남는다(#574). 그 줄을 보고 다시 보내는 일은 #576이 맡는다
    @Override
    public RefundResult startAfterApproval(RefundResult approved) {
        requireNoTransaction();
        if (!RefundStatus.PROCESSING.name().equals(approved.status())) {
            return approved;
        }
        Long refundId = approved.refundId();
        // 주문 줄은 승인 구간이 "시도 중"으로 만들어 두었다(첫 줄은 커밋 뒤 바로 부르므로)
        return runOrderStep(refundId, readRefund(refundId));
    }

    // 주문 단계를 실행하고 이어서 지갑 발행 또는 재고 복구 → 완료까지 간다. 승인 뒤 시작과 환불 번호 입구가 함께 쓴다.
    // 주문 줄은 부르는 쪽이 이미 "시도 중"으로 준비해 두었다(승인 구간이 만들거나, 환불 번호 입구가 다시 시작함)
    private RefundResult runOrderStep(Long refundId, Refund refund) {
        OrderCancellationResult orderResult = runStep(refundId, RefundStepType.ORDER,
                () -> orderCancellationPort.reflectCancellation(new OrderCancellation(
                        refundId, refund.getOrderItemId(), refund.getQuantity(), refund.getDeductedShippingFeeAmount())));
        if (orderResult.rejected()) {
            requireNext(refund, RefundSagaPlan.Event.ORDER_REFUSED, RefundSagaPlan.Action.REJECT);
            return rejectAfterOrderRefused(refundId, refund.getOrderItemId(), orderResult.orderDetail());
        }
        // 다음 단계(돈이 있으면 지갑, 0원이면 재고)는 계획이 정한다. 음수는 여기 오지 않는다(도메인 승인 Refund.approve가 커밋 전에 막음).
        // 계획에 넘기는 환불은 주문을 부르기 전에 읽은 값이라 처리 중이다. 처리 중에서 벗어났는지는 아래 주문 성공 기록 구간이 잠근 뒤 본다
        RefundStepType nextStep = switch (RefundSagaPlan.afterEvent(refund, RefundSagaPlan.Event.ORDER_REFLECTED)) {
            case CALL_WALLET -> RefundStepType.WALLET;
            case CALL_PRODUCT -> RefundStepType.PRODUCT;
            case CALL_ORDER, CALL_WALLET_REVERT, CALL_ORDER_REVERT, COMPLETE, FAIL, REJECT, WAIT, STOP -> throw new IllegalStateException(
                    "주문 반영 뒤 다음 할 일이 지갑·재고가 아닙니다. refundId=" + refundId);
        };
        OrderRecorded recorded = recordOrderSucceeded(refundId, nextStep);
        RefundResult current = recorded.current();
        // 주문 호출 사이에 환불이 처리 중에서 벗어났으면(다른 경로가 먼저 끝냄) 다음 단계를 부르지 않는다.
        // 처리 중이 아닌 환불에 돈을 보내거나 재고를 풀면 끝난 환불이 다시 움직인다. 예외 없이 지금 상태로 돌려주므로 확인용 WARN을 남긴다
        if (!RefundStatus.PROCESSING.name().equals(current.status())) {
            log.warn("주문 반영 뒤 환불이 처리 중이 아니라 다음 단계를 부르지 않음. refundId={}, status={}", refundId, current.status());
            return current;
        }
        // 주문 줄이 없으면 승인 구간이 만든 줄이 사라진 것이라 있을 수 없는 상태다(#616 결정 13). 진행하지 않고 사람이 보게 한다
        if (recorded.orderStepMissing()) {
            log.warn("주문 반영 뒤 주문 단계 줄이 없어 다음 단계를 부르지 않음(있을 수 없는 상태, 확인 필요). refundId={}", refundId);
            return current;
        }
        // 다음 단계 줄이 이미 시도 중·결과 대기·끝 상태면 다시 보내지 않는다(#616 결정 14). 다시 진행 중 겹친 경우라 지금 상태로 응답한다
        if (!recorded.call()) {
            log.info("다음 단계 줄이 할 차례가 아니라 부르지 않음. refundId={}, step={}", refundId, nextStep);
            return current;
        }
        if (nextStep == RefundStepType.WALLET) {
            return publishWallet(refundId, refund, current);
        }
        // 0원은 돌려줄 돈이 없어 지갑을 거치지 않으니 기다릴 메시지도 없다. 예: 취소 금액이 뺄 기본 배송비와 같을 때.
        // 그래서 이 요청 안에서 재고 복구 → 완료까지 간다
        releaseProduct(refundId, refund);
        return completeInRequest(refundId, "0원 재고 복구 뒤 완료");
    }

    // 지갑 반환 요청을 발행하고 지갑 줄을 결과 대기로 바꾼다. 지갑 줄은 부르는 쪽이 이미 "시도 중"으로 준비해 두었다(먼저 기록하고 보내기)
    private RefundResult publishWallet(Long refundId, Refund refund, RefundResult current) {
        // 발행이 끝났어도 지갑이 돈을 돌려줬다는 뜻은 아니다(Kafka에 들어갔을 뿐). 재고 복구와 완료는 지갑의 완료 메시지를 받은 뒤
        // completeAfterWalletRefund가 한다. 그래서 발행 뒤에는 처리 중 그대로 응답한다(계획의 "기다림").
        // 이 확인은 발행 전에 한다. 코드 버그로 걸렸을 때 지갑 요청은 나갔는데 판매자는 오류를 받는 일을 막으려는 것이다
        requireNext(refund, RefundSagaPlan.Event.WALLET_PUBLISHED, RefundSagaPlan.Action.WAIT);
        runStep(refundId, RefundStepType.WALLET, () -> {
            walletRefundRequestPort.requestRefund(new WalletRefundRequest(
                    refundId, refund.getOrderId(), refund.getBuyerId(), refund.getSellerId(), refund.getRefundAmount()));
            return null;
        });
        // 발행은 이미 끝나 되돌릴 수 없다. 여기서 "결과 대기" 기록이 실패해도(DB 오류 등) 판매자에게 오류를 주지 않고 처리 중으로 응답한다.
        // 오류를 주면 지갑 요청은 나갔는데 판매자는 실패로 알고, 응답도 #574 전과 달라진다. 잃는 것은 없다: 지갑 줄은 "시도 중"으로 남고,
        // 지갑 결과 메시지가 와도 시도 중 → 성공·멈춤으로 바뀔 수 있어(결정 2) 흐름이 이어지며, 끝나지 않으면 #576·#577이 그 줄을 본다.
        // 예외를 삼키는 대신 확인이 필요한 이상이라 WARN으로 남긴다(service.md 로그 표). 발행이 실패하면 예외가 나 이 줄에 오지 않는다
        try {
            markAwaitingResult(refundId, RefundStepType.WALLET);
        } catch (RuntimeException e) {
            log.warn("지갑 발행 뒤 결과 대기 기록 실패. 지갑 단계는 시도 중으로 남음. refundId={}", refundId, e);
        }
        return current;
    }

    // 알람이 회수 줄을 다시 집었을 때(앞선 발행 실패, 결과 대기 시간 초과, 시도 중 만료) 회수 요청을 다시 보낸다.
    // 계획 확인이 어긋나면 예외를 올린다. 알람이 환불마다 잡아 로그로 남긴다(#616 결정 16)
    private RefundResult resendWalletRevert(Long refundId, Refund refund, RefundResult current) {
        requireNext(refund, RefundSagaPlan.Event.WALLET_REVERT_PUBLISHED, RefundSagaPlan.Action.WAIT);
        publishWalletRevert(refundId, refund);
        return current;
    }

    // 지갑에 돈 다시 가져오기(회수) 요청을 Kafka로 발행하고 회수 줄을 결과 대기로 바꾼다(#546). 회수 줄은 부르는 쪽이 이미 "시도 중"으로 준비해 두었다
    // (처음은 재고 거절을 기록한 구간이 만들고, 다시 보낼 때는 resumeStep이 준비한다).
    // 반환 요청 발행(publishWallet)과 같은 틀이다. 발행이 실패하면 틀(runStep)이 회수 줄을 할 차례로 돌려 알람이 다시 보내고(5번까지),
    // 결과를 5분 기다려도 오지 않으면 같은 refundId로 다시 보낸다(#577 규칙, 지갑이 refundId로 한 번만 가져옴).
    // 보내는 금액·구매자는 반환 요청과 같은 출처(환불 행)에서 꺼낸다. 지갑 완료는 이 값과 같을 때만 받았으므로 돌려준 금액과 같다
    private void publishWalletRevert(Long refundId, Refund refund) {
        runStep(refundId, RefundStepType.WALLET_REVERT, () -> {
            walletRefundRequestPort.requestRevert(new WalletRevertRequest(
                    refundId, refund.getOrderId(), refund.getBuyerId(), refund.getRefundAmount()));
            return null;
        });
        // 반환 요청 발행 뒤와 같은 이유로, 결과 대기 기록이 실패해도 예외를 올리지 않는다. 회수 줄은 시도 중으로 남아 만료(2분) 뒤 알람이 다시 집는다
        try {
            markAwaitingResult(refundId, RefundStepType.WALLET_REVERT);
        } catch (RuntimeException e) {
            log.warn("지갑 회수 발행 뒤 결과 대기 기록 실패. 회수 단계는 시도 중으로 남음. refundId={}", refundId, e);
        }
        // INFO인 이유: 결과가 끝내 안 오면 24시간 동안 5분마다 다시 보내 많다(최대 288줄, #577과 같음). 회수를 시작한 사실은 회수 줄을 만들 때 WARN으로 남고,
        // 사람에게 알리는 일은 멈춘 환불 찾기가 맡는다(멈춘 재고 줄)
        log.info("지갑에 돈 다시 가져오기를 요청함. refundId={}, orderId={}, amount={}",
                refundId, refund.getOrderId(), refund.getRefundAmount());
    }

    // 알람이 되돌리기 줄을 다시 집었을 때(앞선 발행 실패, 결과 대기 시간 초과, 시도 중 만료) 주문 되돌리기 요청을 다시 보낸다(#622).
    // 계획 확인이 어긋나면 예외를 올린다. 알람이 환불마다 잡아 로그로 남긴다(#616 결정 16)
    private RefundResult resendOrderRevert(Long refundId, Refund refund, RefundResult current) {
        requireNext(refund, RefundSagaPlan.Event.ORDER_REVERT_PUBLISHED, RefundSagaPlan.Action.WAIT);
        publishOrderRevert(refundId, refund);
        return current;
    }

    // 주문에 취소 기록 되돌리기 요청을 Kafka로 발행하고 되돌리기 줄을 결과 대기로 바꾼다(#622). 되돌리기 줄은 부르는 쪽이 이미 "시도 중"으로
    // 준비해 두었다(처음은 지갑 실패·회수 성공을 기록한 구간이 만들고, 다시 보낼 때는 resumeStep이 준비한다).
    // 회수 요청 발행(publishWalletRevert)과 같은 틀이다. 발행이 실패하면 틀(runStep)이 줄을 할 차례로 돌려 알람이 다시 보내고(5번까지),
    // 결과를 5분 기다려도 오지 않으면 같은 refundId로 다시 보낸다(주문이 refundId로 한 번만 되돌림, PR #537 약속).
    // 보내는 값은 취소 반영 때와 같은 출처(환불 행)에서 꺼낸다. 주문은 refundId로 되돌릴 기록을 찾고, 수량은 확인용이다
    private void publishOrderRevert(Long refundId, Refund refund) {
        runStep(refundId, RefundStepType.ORDER_REVERT, () -> {
            orderRevertRequestPort.requestRevert(new OrderRevertRequest(
                    refundId, refund.getOrderId(), refund.getOrderItemId(), refund.getQuantity()));
            return null;
        });
        // 다른 발행 뒤와 같은 이유로, 결과 대기 기록이 실패해도 예외를 올리지 않는다. 줄은 시도 중으로 남아 만료(2분) 뒤 알람이 다시 집는다
        try {
            markAwaitingResult(refundId, RefundStepType.ORDER_REVERT);
        } catch (RuntimeException e) {
            log.warn("주문 되돌리기 발행 뒤 결과 대기 기록 실패. 되돌리기 단계는 시도 중으로 남음. refundId={}", refundId, e);
        }
        // INFO인 이유: 결과가 끝내 안 오면 24시간 동안 5분마다 다시 보내 많다(회수와 같음). 시작한 사실은 줄을 만들 때 WARN으로 남는다
        log.info("주문에 취소 기록 되돌리기를 요청함. refundId={}, orderId={}, orderItemId={}, quantity={}",
                refundId, refund.getOrderId(), refund.getOrderItemId(), refund.getQuantity());
    }

    // 되돌리기 요청을 바로 보낸다(회수의 sendWalletRevertRightAway와 같은 규칙). 앞선 결과 기록은 이미 커밋돼 있어, 실패해도 예외를 올리지 않는다.
    // 올리면 결과 메시지의 오류 처리기가 같은 결과를 다시 시도하지만, 결과는 이미 기록돼 넘겨질 뿐이다.
    // 계획 확인이 어긋나면 코드 버그라 ERROR, 발행 실패는 되돌리기 줄에 이미 기록됐으니(할 차례 → 알람이 다시 보냄) WARN이다
    private void sendOrderRevertRightAway(Long refundId, Refund refund) {
        try {
            requireNext(refund, RefundSagaPlan.Event.ORDER_REVERT_PUBLISHED, RefundSagaPlan.Action.WAIT);
        } catch (IllegalStateException planBug) {
            log.error("주문 되돌리기 요청 전 계획 확인이 어긋나 보내지 않음(코드 버그, 되돌리기 줄은 시도 중으로 남아 만료 뒤 알람이 다시 집음). refundId={}",
                    refundId, planBug);
            return;
        }
        try {
            publishOrderRevert(refundId, refund);
        } catch (RuntimeException sendError) {
            log.warn("주문 되돌리기 요청을 바로 보내지 못함. 되돌리기 줄에 기록했고 알람이 다시 보냄. refundId={}", refundId, sendError);
        }
    }

    // 되돌리기 줄을 시도 중으로 만든다(#622). 앞선 결과(지갑 반환 실패, 회수 성공)를 기록한 같은 구간에서 만들어,
    // 결과만 남고 되돌리기 줄이 빠지는 일이 없게 한다. 보내기는 구간이 커밋된 뒤 부르는 쪽이 바로 한다(먼저 기록하고 보내기).
    // 시도 중으로 만드는 이유: 보내는 동안 알람이 같은 줄을 또 집지 않게 한다. 보내다 서버가 죽으면 2분 뒤 만료돼 알람이 다시 집는다(#576).
    // 다음 할 일은 계획이 정한다. 줄이 이미 있으면 다시 만들지 않는다((refund_id, step) 유일 제약)
    private boolean startOrderRevert(Long refundId, Refund refund, RefundSagaPlan.Event event, LocalDateTime now) {
        if (RefundSagaPlan.afterEvent(refund, event) != RefundSagaPlan.Action.CALL_ORDER_REVERT) {
            log.warn("주문 되돌리기를 시작하려 했지만 계획이 다른 일을 정해 시작하지 않음(확인 필요). refundId={}, event={}", refundId, event);
            return false;
        }
        if (refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER_REVERT).isPresent()) {
            return false;
        }
        refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER_REVERT, now));
        log.warn("승인 뒤 단계가 끝내 실패해 주문 취소 기록 되돌리기를 시작함(확인 필요). refundId={}, event={}", refundId, event);
        return true;
    }

    // 재고를 되돌린다. 재고 줄은 부르는 쪽이 이미 "시도 중"으로 준비해 두었다. 다음 할 일(완료)은 계획에 확인한다
    private void releaseProduct(Long refundId, Refund refund) {
        runStep(refundId, RefundStepType.PRODUCT, () -> {
            productStockReleasePort.releaseStock(new StockRelease(
                    refundId, refund.getOrderId(), refund.getProductOptionId(), refund.getQuantity()));
            return null;
        });
        requireNext(refund, RefundSagaPlan.Event.PRODUCT_RELEASED, RefundSagaPlan.Action.COMPLETE);
    }

    // 요청 안에서(승인 뒤 0원, 환불 번호 입구) 재고까지 마친 환불을 완료로 바꾸고 결과를 돌려준다. 환불이 없으면 코드 버그다
    private RefundResult completeInRequest(Long refundId, String what) {
        Closing closing = completeWithProductStep(refundId);
        if (!closing.found()) {
            throw new IllegalStateException("승인한 환불을 완료하려는데 찾을 수 없습니다. refundId=" + refundId);
        }
        logClosing(closing, refundId, what);
        return closing.result();
    }

    // 알람(#576)이 깨우면 단계 줄을 보고 다음 할 일을 정해 그 단계부터 이어 간다. 판단은 계획(fromSteps)이 하고,
    // 실행은 승인 뒤 시작·지갑 완료와 같은 메서드(runOrderStep, publishWallet, releaseProduct)를 쓴다.
    // 다시 부를 단계 줄은 잠근 구간에서 먼저 준비한다(할 차례 → 다시 시작, 시도 횟수 +1). 그사이 다른 경로가 먼저 시작했으면 부르지 않는다.
    // 실패하면 예외를 그대로 올린다. 환불마다 예외를 잡고 다음 환불로 넘어가는 일은 알람이 한다(#616 결정 16)
    @Override
    public RefundResult resume(Long refundId) {
        requireNoTransaction();
        Refund refund = readRefund(refundId);
        List<RefundStep> steps = readOnlyTransaction.execute(status -> refundStepPersistencePort.findAllByRefundId(refundId));
        RefundSagaPlan.Action action = RefundSagaPlan.fromSteps(refund, steps, LocalDateTime.now(clock));
        return switch (action) {
            case CALL_ORDER -> resumeStep(refundId, RefundStepType.ORDER, current -> runOrderStep(refundId, refund));
            case CALL_WALLET -> resumeStep(refundId, RefundStepType.WALLET, current -> publishWallet(refundId, refund, current));
            case CALL_PRODUCT -> resumeStep(refundId, RefundStepType.PRODUCT, current -> {
                releaseProduct(refundId, refund);
                return completeInRequest(refundId, "다시 진행에서 재고 복구 뒤 완료");
            });
            case CALL_WALLET_REVERT -> resumeStep(refundId, RefundStepType.WALLET_REVERT,
                    current -> resendWalletRevert(refundId, refund, current));
            case CALL_ORDER_REVERT -> resumeStep(refundId, RefundStepType.ORDER_REVERT,
                    current -> resendOrderRevert(refundId, refund, current));
            case COMPLETE -> completeInRequest(refundId, "다시 진행에서 완료");
            case WAIT -> {
                log.info("다시 진행할 단계가 시도 중이거나 지갑 결과를 기다리는 중(기다리는 시각 전)이라 넘김. refundId={}", refundId);
                yield RefundResult.from(refund);
            }
            case STOP -> {
                log.warn("다시 진행할 수 없는 환불이라 넘김(멈춘 단계, 단계 줄 없음 또는 처리 중 아님, 확인 필요). refundId={}, status={}",
                        refundId, refund.getStatus());
                stopBlockedSteps(refundId);
                yield RefundResult.from(refund);
            }
            case REJECT -> throw new IllegalStateException("단계 줄로는 거절을 정하지 않습니다. refundId=" + refundId);
            // 실패로 끝내는 일은 주문의 되돌림 결과를 받은 구간이 되돌리기 줄 성공과 함께 한다. 단계 줄만 보고는 정하지 않는다
            case FAIL -> throw new IllegalStateException("단계 줄로는 실패를 정하지 않습니다. refundId=" + refundId);
        };
    }

    // 다시 진행할 수 없다고 판단한 처리 중 환불(예: 주문 줄은 멈췄는데 재고 줄이 할 차례)에서,
    // 알람이 다시 집을 줄을 멈춤으로 바꾼다(PR #628 검수). 다시 집을 줄은 할 차례, 만료된 시도 중, 기다리는 시각이 지난 결과 대기(#577)다. 그대로 두면 그 줄의 시각이 바뀌지 않아 알람 매 회차 맨 앞에 다시 뽑히고, 쌓이면 다른 환불을 밀어낸다.
    // 멈춤으로 바꾸면 사람이 볼 환불로 드러난다. 만료되지 않은 시도 중 줄은 누군가 하는 중이라 건드리지 않는다.
    // 단, 이미 보낸 줄(awaitingSince 있음)은 멈추지 않고 결과 대기로 두되 다시 볼 시각을 비운다(#546, PR #654 2차 봇 리뷰 1번).
    // 멈추면 늦게 온 진짜 지갑 답을 기록하지 못한다(#577 결정 3). 다시 볼 시각이 없으면 알람이 더 뽑지 않아 위의 문제도 생기지 않는다.
    // 회수 줄(#546)이 끝난 뒤(다시 가져옴·실패)에는 계획이 멈춤을 정한다. 그때 결과 대기로 남은 회수 줄은 없지만(끝 상태), 같은 규칙으로 지킨다
    // 잠근 뒤 처리 중이 아니면 쓰지 않는다(#616 결정 15). 끝난 환불은 알람이 고르지도 않는다
    private void stopBlockedSteps(Long refundId) {
        transaction.executeWithoutResult(status -> {
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(refundId);
            if (locked.isEmpty() || locked.get().getStatus() != RefundStatus.PROCESSING) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            for (RefundStep step : refundStepPersistencePort.findAllByRefundId(refundId)) {
                boolean blocked = step.getStatus() == RefundStepStatus.READY || step.isExpired(now) || step.isResultOverdue(now);
                if (!blocked) {
                    continue;
                }
                if (step.holdForLateResult()) {
                    refundStepPersistencePort.update(step);
                    log.warn("다시 진행할 수 없는 환불의 이미 보낸 단계는 멈추지 않고 늦은 결과만 기다림(확인 필요). refundId={}, step={}",
                            refundId, step.getType());
                    continue;
                }
                if (step.stop(BLOCKED_BY_STOPPED_STEP)) {
                    refundStepPersistencePort.update(step);
                    log.warn("다시 진행할 수 없는 환불의 남은 단계를 멈춤(확인 필요). refundId={}, step={}", refundId, step.getType());
                }
            }
        });
    }

    // 다시 부를 단계 줄을 잠근 구간에서 준비한 뒤, 부를 차례면 실행한다. 준비하는 사이 다른 경로가 먼저 시작했거나(시도 중·결과 대기)
    // 끝났거나(끝 상태) 환불이 처리 중에서 벗어났으면 부르지 않고 지금 상태를 돌려준다
    private RefundResult resumeStep(Long refundId, RefundStepType type, Function<RefundResult, RefundResult> run) {
        Prepared prepared = transaction.execute(status -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new IllegalStateException("다시 진행할 환불이 없습니다. refundId=" + refundId));
            if (locked.getStatus() != RefundStatus.PROCESSING) {
                return new Prepared(RefundResult.from(locked), null);
            }
            return new Prepared(RefundResult.from(locked), prepareStep(refundId, type, LocalDateTime.now(clock)));
        });
        if (prepared.prep() == StepPrep.CALL) {
            return run.apply(prepared.current());
        }
        if (prepared.prep() == null) {
            log.warn("다시 진행하려는 사이 환불이 처리 중에서 벗어나 넘김. refundId={}, status={}", refundId, prepared.current().status());
        } else {
            log.info("다시 진행하려는 사이 단계가 이미 시작됐거나 끝나 넘김. refundId={}, step={}, prep={}", refundId, type, prepared.prep());
        }
        return prepared.current();
    }

    @Override
    public void completeAfterWalletRefund(WalletRefundCompletedCommand command) {
        requireNoTransaction();
        Long refundId = command.refundId();
        Snapshot snapshot = readSnapshot(refundId);
        // 받을지는 정책이 정한다(#616). 금액·주문·구매자가 다르면 완료하지 않고 불일치로 남긴다. 돈이 어긋난 채 끝내면 되돌릴 방법이 없어 사람이 보게 한다
        // (지갑에 그 반환을 다시 가져와 달라고 보내는 것은 #572). 실패·불일치와 달리 "재고 줄 있음"은 거르지 않는다(정책 설명 참고).
        // 재고가 실패해 "할 차례"로 남은 환불에 같은 완료 메시지가 다시 오면(지갑이 다시 보냄) 재고를 다시 시도해야 하기 때문이다
        WalletResultPolicy.Verdict verdict = WalletResultPolicy.judge(WalletResultPolicy.Kind.COMPLETED, snapshot.refund(),
                snapshot.walletStep(), snapshot.productStep(), command.orderId(), command.buyerId(), command.amount(), null);
        if (verdict.decision() == WalletResultPolicy.Decision.SKIP) {
            logSkipped(verdict.skipReason(), WalletResultPolicy.Kind.COMPLETED, refundId, snapshot, null);
            return;
        }
        if (verdict.decision() == WalletResultPolicy.Decision.MISMATCH) {
            recordMismatch(WalletResultPolicy.Kind.COMPLETED, refundId, command.orderId(), command.buyerId(), command.amount(),
                    null, snapshot.refund());
            return;
        }
        Refund refund = snapshot.refund();
        requireNext(refund, RefundSagaPlan.Event.WALLET_COMPLETED, RefundSagaPlan.Action.CALL_PRODUCT);
        // 재고 줄 상태는 startProductStep이 본다(#616 결정 14)
        ProductStart started = startProductStep(refundId, command);
        if (started != ProductStart.STARTED) {
            logProductNotStarted(started, refundId);
            return;
        }
        // 재고 복구는 구간 밖. 상품은 같은 refundId면 또 풀지 않아 같은 메시지가 다시 와도 안전하다.
        // 실패하면 틀(runStep)이 재고 줄에 실패를 기록하고(다음 시도 시각, 또는 다시 해도 같은 실패면 멈춤) 예외를 올린다.
        // 여기서 예외를 삼키고 메시지 처리를 끝낸다(#576). 다시 시도는 알람 한 곳이 맡는다. 던지면 Kafka 오류 처리기가 31초 동안 5번
        // 다시 시도하며 그때마다 재고 줄 시도 횟수가 올라, 알람이 손대기 전에 횟수를 다 써 멈춘다. 그동안 같은 파티션의 뒤 메시지도 기다린다.
        // 기록마저 실패해 재고 줄이 시도 중으로 남아도, 만료(2분) 뒤 알람이 다시 집는다
        try {
            releaseProduct(refundId, refund);
        } catch (RestApiException e) {
            // 다시 해도 같은 실패면 재고 줄은 이미 멈춤이고, 그 밖의 실패면 할 차례로 남아 알람이 다시 시도한다
            log.warn("지갑 완료 뒤 재고 복구가 실패해 기록하고 넘김(다시 해도 같은 실패면 멈춤, 아니면 알람이 다시 시도). refundId={}, errorCode={}",
                    refundId, e.getErrorCode().name());
            return;
        } catch (RuntimeException e) {
            // 다른 모듈 응답이 아닌 예외는 코드 버그일 수 있어 ERROR로 남긴다. 다시 시도는 위와 같이 알람이 한다
            log.error("지갑 완료 뒤 재고 복구 중 예상하지 못한 오류. 알람의 다시 시도로 넘김. refundId={}", refundId, e);
            return;
        }
        Closing closing = completeWithProductStep(refundId);
        // 그사이 같은 메시지가 먼저 완료한 경우는 정상 중복이라 INFO다
        if (!closing.changed()) {
            log.info("재고 복구를 마친 사이 환불이 이미 바뀌어 완료하지 않고 넘김. refundId={}", refundId);
            return;
        }
        if (closing.stepMissing()) {
            log.warn("지갑 완료 뒤 완료하는데 재고 단계 줄이 없음(있을 수 없는 상태, 확인 필요). refundId={}", refundId);
        }
    }

    @Override
    public void recordWalletRefundFailure(WalletRefundFailedCommand command) {
        requireNoTransaction();
        Long refundId = command.refundId();
        Snapshot snapshot = readSnapshot(refundId);
        // 받을지는 정책이 정한다(#616). 금액·주문·구매자가 다르면 메시지의 어느 값이 틀렸는지 알 수 없다. 지갑 결과가 어긋난 것 자체가 문제라
        // 완료 경로와 같이 불일치로 기록해 사람이 보게 한다(엉뚱한 환불에 표시가 붙을 수 있음은 감수, #544 결정 7·12).
        // 실패 메시지의 금액은 환불이 요청한 금액을 그대로 돌려준다고 본다(지갑 제안에 뜻이 없어 둔 가정, 결정 12).
        // 지갑이 알려 준 이유는 기록 대신 로그에 남는다. 예외를 던지면 오류 처리기가 같은 메시지를 다시 시도해 줄이 막히므로 던지지 않는다
        WalletResultPolicy.Verdict verdict = WalletResultPolicy.judge(WalletResultPolicy.Kind.FAILED, snapshot.refund(),
                snapshot.walletStep(), snapshot.productStep(), command.orderId(), command.buyerId(), command.amount(), command.reason());
        if (verdict.decision() == WalletResultPolicy.Decision.SKIP) {
            logSkipped(verdict.skipReason(), WalletResultPolicy.Kind.FAILED, refundId, snapshot, command.reason());
            return;
        }
        if (verdict.decision() == WalletResultPolicy.Decision.MISMATCH) {
            recordMismatch(WalletResultPolicy.Kind.FAILED, refundId, command.orderId(), command.buyerId(), command.amount(),
                    command.reason(), snapshot.refund());
            return;
        }
        WalletFailureMarked marked = transaction.execute(status -> {
            // 읽은 뒤 잠그기 전에 상태나 기록이 바뀔 수 있어 잠근 뒤 같은 정책으로 다시 본다. 이 행을 잠그는 첫 조회라 옛 값을 쓰지 않는다
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElse(null);
            // 단계 줄은 환불 행을 잠근 뒤에 읽는다(결정 4, 잠금 순서 환불 행 → 단계 줄)
            Optional<RefundStep> walletStep = locked == null ? Optional.empty()
                    : refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET);
            RefundStep productStep = locked == null ? null : findProductStep(refundId);
            WalletResultPolicy.Verdict again = WalletResultPolicy.judge(WalletResultPolicy.Kind.FAILED, locked,
                    walletStep.orElse(null), productStep, command.orderId(), command.buyerId(), command.amount(), command.reason());
            // 그사이 완료됐거나 재고 단계가 시작됐으면 "바뀜"(INFO)이 아니라 읽을 때 그랬던 경우와 같은 모순(WARN)이다
            if (isAfterWalletSuccess(again)) {
                logIgnoredAfterWalletSuccess(locked, productStep, "실패");
                return new WalletFailureMarked(MarkOutcome.AFTER_WALLET_SUCCESS, null);
            }
            if (again.decision() != WalletResultPolicy.Decision.ACCEPT) {
                return new WalletFailureMarked(MarkOutcome.CHANGED, null);
            }
            // 환불 행은 바꾸지 않는다(실패는 지갑 줄에만 남는다, 결정 3). 환불 행은 단계 줄을 지키려고 잠근 것이다
            MarkOutcome stopped = stopWalletStep(walletStep, command.reason());
            // 지갑이 반환에 실패했으면 돈이 나가지 않아 지갑은 되돌릴 것이 없으니 바로 주문 취소 기록을 되돌린다(#622, PR #537 표).
            // 지갑 줄을 처음 멈춘 경우만 시작한다. 이미 다른 이유(결과 불일치, 발행 횟수를 다 씀, 막힘)로 멈춘 줄은 지갑이 실패했다고
            // 확정할 수 없어 사람이 본다(#622 결정 D2)
            if (stopped == MarkOutcome.MARKED && startOrderRevert(refundId, locked, RefundSagaPlan.Event.WALLET_FAILED,
                    LocalDateTime.now(clock))) {
                return new WalletFailureMarked(stopped, locked);
            }
            return new WalletFailureMarked(stopped, null);
        });
        MarkOutcome outcome = marked.outcome();
        if (outcome == MarkOutcome.AFTER_WALLET_SUCCESS) {
            return;
        }
        if (outcome == MarkOutcome.ALREADY_STOPPED) {
            logKeptFirstStopReason(refundId, command.reason());
            return;
        }
        if (outcome == MarkOutcome.NO_WALLET_STEP) {
            logNoWalletStep(refundId, command.reason());
            return;
        }
        if (outcome != MarkOutcome.MARKED) {
            log.info("지갑 반환 실패를 기록하려는 사이 환불이 바뀌어 넘김. refundId={}", refundId);
            return;
        }
        // 구간이 커밋된 뒤에 남긴다. 구간이 실패했는데 "기록함"이 남으면 실제와 어긋난다
        log.warn("지갑 반환 실패를 지갑 단계 줄에 기록함(환불은 처리 중 유지). refundId={}, orderId={}, amount={}, reason={}",
                refundId, command.orderId(), command.amount(), command.reason());
        if (marked.orderRevertTarget() != null) {
            sendOrderRevertRightAway(refundId, marked.orderRevertTarget());
        }
    }

    // 지갑이 돈을 다시 가져왔다(#546). 회수 줄을 성공으로 남기고, 같은 구간에서 주문 되돌리기 줄을 만들어 커밋 뒤 바로 보낸다(#622).
    // 환불은 주문이 되돌렸다고 알릴 때까지 처리 중이다
    @Override
    public void recordWalletRevertCompleted(WalletRevertCompletedCommand command) {
        requireNoTransaction();
        recordRevertResult(WalletRevertResultPolicy.Kind.REVERTED, command.refundId(), command.orderId(), command.buyerId(),
                command.amount(), null);
    }

    // 지갑이 돈을 다시 가져오지 못했다(예: 잔액 부족). 회수 줄을 지갑이 알려 준 이유로 멈춘다. 주문은 되돌리지 않고 사람이 본다(11/15 합의)
    @Override
    public void recordWalletRevertFailure(WalletRevertFailedCommand command) {
        requireNoTransaction();
        recordRevertResult(WalletRevertResultPolicy.Kind.FAILED, command.refundId(), command.orderId(), command.buyerId(),
                command.amount(), command.reason());
    }

    // 회수 결과 두 종류를 같은 규칙으로 회수 줄에 남긴다(한쪽만 고쳐 동작이 갈라지지 않게). 다른 모듈을 부르지 않아 잠근 구간 하나로 끝낸다.
    // 걸러 넘기는 경우는 예외를 던지지 않는다. 던지면 메시지 오류 처리기가 같은 메시지를 다시 시도해 줄만 막힌다(지갑 반환 결과와 같음).
    // 로그는 구간이 커밋된 뒤에 남긴다
    private void recordRevertResult(WalletRevertResultPolicy.Kind kind, Long refundId, Long orderId, Long buyerId, long amount,
                                    String walletReason) {
        RevertRecorded recorded = transaction.execute(status -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElse(null);
            Optional<RefundStep> revertStep = locked == null ? Optional.empty()
                    : refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET_REVERT);
            WalletRevertResultPolicy.Verdict verdict = WalletRevertResultPolicy.judge(locked, revertStep.orElse(null),
                    orderId, buyerId, amount);
            if (verdict.decision() == WalletRevertResultPolicy.Decision.SKIP) {
                return new RevertRecorded(verdict, locked == null ? null : locked.getStatus(),
                        revertStep.map(RefundStep::getStatus).orElse(null), null);
            }
            RefundStep step = revertStep.get();
            if (verdict.decision() == WalletRevertResultPolicy.Decision.MISMATCH) {
                step.stop(RESULT_MISMATCH);
            } else if (kind == WalletRevertResultPolicy.Kind.REVERTED) {
                step.succeed();
            } else {
                step.stop(walletReason);
            }
            refundStepPersistencePort.update(step);
            // 돈을 다시 가져왔으면 이어서 주문 취소 기록을 되돌린다(#622, 되돌리는 순서 지갑 → 주문). 회수 줄 성공과 같은 구간에서
            // 되돌리기 줄을 만들어, 회수만 성공으로 남고 다음 줄이 빠지는 일이 없게 한다. 실패·불일치는 주문을 되돌리지 않는다
            // (돈이 구매자에게 남았거나 회수 결과를 믿을 수 없다, PR #537 표)
            boolean revertOrder = verdict.decision() == WalletRevertResultPolicy.Decision.ACCEPT
                    && kind == WalletRevertResultPolicy.Kind.REVERTED
                    && startOrderRevert(refundId, locked, RefundSagaPlan.Event.WALLET_REVERTED, LocalDateTime.now(clock));
            return new RevertRecorded(verdict, locked.getStatus(), step.getStatus(), revertOrder ? locked : null);
        });
        logRevertRecorded(recorded, kind, refundId, orderId, buyerId, amount, walletReason);
        if (recorded.orderRevertTarget() != null) {
            sendOrderRevertRightAway(refundId, recorded.orderRevertTarget());
        }
    }

    // 넘긴 이유와 남긴 결과를 로그로 남긴다. 회수를 받은 것은 돈이 다시 움직인 일이라 사람이 알아야 해 WARN이다
    // (다시 가져옴이면 이어서 주문을 되돌리고, 실패면 사람이 본다). 같은 결과가 다시 온 것만 정상 중복이라 INFO다
    private static void logRevertRecorded(RevertRecorded recorded, WalletRevertResultPolicy.Kind kind, Long refundId,
                                          Long orderId, Long buyerId, long amount, String walletReason) {
        String label = kind == WalletRevertResultPolicy.Kind.REVERTED ? "다시 가져옴" : "실패";
        WalletRevertResultPolicy.Verdict verdict = recorded.verdict();
        if (verdict.decision() == WalletRevertResultPolicy.Decision.MISMATCH) {
            log.warn("지갑 회수 {} 결과가 환불과 달라 회수 줄을 불일치로 멈춤(확인 필요). refundId={}, 메시지 orderId={}, 메시지 buyerId={}, "
                    + "메시지 금액={}, 지갑 이유={}", label, refundId, orderId, buyerId, amount, walletReason);
            return;
        }
        if (verdict.decision() == WalletRevertResultPolicy.Decision.ACCEPT) {
            String next = kind == WalletRevertResultPolicy.Kind.REVERTED
                    ? "이어서 주문 취소 기록을 되돌림" : "주문은 되돌리지 않고 환불은 처리 중으로 두어 사람이 봄";
            log.warn("지갑 회수 {}를 회수 줄에 기록함. {}(확인 필요). refundId={}, amount={}, 지갑 이유={}",
                    label, next, refundId, amount, walletReason);
            return;
        }
        switch (verdict.skipReason()) {
            case REFUND_NOT_FOUND -> log.warn("지갑 회수 {} 메시지의 환불이 없어 넘김. refundId={}", label, refundId);
            case NOT_PROCESSING -> log.warn("처리 중이 아닌 환불의 지갑 회수 {} 메시지라 넘김(확인 필요). refundId={}, status={}",
                    label, refundId, recorded.refundStatus());
            case NO_REVERT_STEP -> log.warn("회수 줄이 없는 환불에 지갑 회수 {} 메시지가 와 넘김(있을 수 없는 상태, 확인 필요). refundId={}",
                    label, refundId);
            case ALREADY_FINISHED -> {
                boolean sameResult = kind == WalletRevertResultPolicy.Kind.REVERTED
                        ? recorded.stepStatus() == RefundStepStatus.SUCCEEDED
                        : recorded.stepStatus() == RefundStepStatus.STOPPED;
                if (sameResult) {
                    log.info("회수 줄이 이미 같은 결과로 끝나 지갑 회수 {} 메시지를 넘김. refundId={}", label, refundId);
                } else {
                    log.warn("회수 줄이 이미 다른 결과로 끝나 처음 결과를 그대로 두고 넘김(지갑 결과가 서로 다름, 확인 필요). refundId={}, stepStatus={}, 지갑 이유={}",
                            refundId, recorded.stepStatus(), walletReason);
                }
            }
        }
    }

    // 주문이 취소 기록을 되돌렸다(#622). 되돌리기 줄 성공과 환불 실패를 같은 구간에서 남긴다
    @Override
    public void recordOrderRefundReverted(OrderRefundRevertedCommand command) {
        requireNoTransaction();
        recordOrderRevertResult(OrderRevertResultPolicy.Kind.REVERTED, command.refundId(), command.orderId(),
                command.orderItemId(), null);
    }

    // 주문이 취소 기록을 되돌리지 못했다(예: 이미 구매확정). 되돌리기 줄을 주문이 알려 준 이유로 멈추고 환불은 처리 중으로 둔다.
    // 되돌리기를 또 되돌리지는 않는다(PR #537). 멈춘 줄이 있어 멈춘 환불 찾기가 사람에게 알린다
    @Override
    public void recordOrderRefundRevertFailure(OrderRefundRevertFailedCommand command) {
        requireNoTransaction();
        recordOrderRevertResult(OrderRevertResultPolicy.Kind.FAILED, command.refundId(), command.orderId(),
                command.orderItemId(), command.reason());
    }

    // 주문 되돌리기 결과 두 종류를 같은 규칙으로 남긴다(회수 결과와 같은 틀). 다른 모듈을 부르지 않아 잠근 구간 하나로 끝낸다.
    // 걸러 넘기는 경우는 예외를 던지지 않는다. 던지면 메시지 오류 처리기가 같은 메시지를 다시 시도해 줄만 막힌다. 로그는 구간이 커밋된 뒤에 남긴다.
    // 한계: 처리 중 → 실패는 같은 묶음의 배송비 집계를 바꾸는데도(실패는 집계에 들지 않음) 자기 행만 잠근다. 승인 뒤 주문 거절과 같은 한계다
    // (RefundShippingFeePolicy.CANCELLING_STATUSES 주석, TODO(#707))
    private void recordOrderRevertResult(OrderRevertResultPolicy.Kind kind, Long refundId, Long orderId, Long orderItemId,
                                         String orderReason) {
        OrderRevertRecorded recorded = transaction.execute(status -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElse(null);
            Optional<RefundStep> revertStep = locked == null ? Optional.empty()
                    : refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER_REVERT);
            OrderRevertResultPolicy.Verdict verdict = OrderRevertResultPolicy.judge(locked, revertStep.orElse(null),
                    orderId, orderItemId);
            if (verdict.decision() == OrderRevertResultPolicy.Decision.SKIP) {
                return new OrderRevertRecorded(verdict, locked == null ? null : locked.getStatus(),
                        revertStep.map(RefundStep::getStatus).orElse(null),
                        kind == OrderRevertResultPolicy.Kind.FAILED && revertStep.map(s -> s.isStoppedWith(orderReason)).orElse(false));
            }
            RefundStep step = revertStep.get();
            if (verdict.decision() == OrderRevertResultPolicy.Decision.MISMATCH) {
                step.stop(RESULT_MISMATCH);
            } else if (kind == OrderRevertResultPolicy.Kind.REVERTED) {
                requireNext(locked, RefundSagaPlan.Event.ORDER_REVERTED, RefundSagaPlan.Action.FAIL);
                step.succeed();
                // 되돌리기 줄 성공과 환불 실패를 같은 구간에서 함께 저장한다(#574 결정 5, 완료·거절과 같은 규칙). 따로 저장하면 하나만 남아 표와 환불이 어긋날 수 있다
                locked.failAfterApproval(Refund.FAILED_REASON);
                refundPersistencePort.update(locked);
            } else {
                step.stop(orderReason);
            }
            refundStepPersistencePort.update(step);
            return new OrderRevertRecorded(verdict, locked.getStatus(), step.getStatus(), false);
        });
        logOrderRevertRecorded(recorded, kind, refundId, orderId, orderItemId, orderReason);
    }

    // 넘긴 이유와 남긴 결과를 로그로 남긴다. 실패로 끝낸 환불과 되돌리지 못한 환불은 사람이 알아야 해 WARN이다.
    // 같은 결과가 다시 온 것(주문이 같은 refundId에 결과를 다시 보냄)만 정상 중복이라 INFO다
    private static void logOrderRevertRecorded(OrderRevertRecorded recorded, OrderRevertResultPolicy.Kind kind, Long refundId,
                                               Long orderId, Long orderItemId, String orderReason) {
        String label = kind == OrderRevertResultPolicy.Kind.REVERTED ? "되돌림" : "실패";
        OrderRevertResultPolicy.Verdict verdict = recorded.verdict();
        if (verdict.decision() == OrderRevertResultPolicy.Decision.MISMATCH) {
            log.warn("주문 되돌리기 {} 결과가 환불과 달라 되돌리기 줄을 불일치로 멈춤(확인 필요). refundId={}, 메시지 orderId={}, "
                    + "메시지 orderItemId={}, 주문 이유={}", label, refundId, orderId, orderItemId, orderReason);
            return;
        }
        if (verdict.decision() == OrderRevertResultPolicy.Decision.ACCEPT) {
            if (kind == OrderRevertResultPolicy.Kind.REVERTED) {
                log.warn("주문이 취소 기록을 되돌려 환불을 실패로 끝냄(확인 필요). refundId={}, orderId={}, orderItemId={}",
                        refundId, orderId, orderItemId);
            } else {
                log.warn("주문이 취소 기록을 되돌리지 못해 되돌리기 줄을 멈춤. 환불은 처리 중으로 두고 사람이 봄(확인 필요). refundId={}, 주문 이유={}",
                        refundId, orderReason);
            }
            return;
        }
        switch (verdict.skipReason()) {
            case REFUND_NOT_FOUND -> log.warn("주문 되돌리기 {} 메시지의 환불이 없어 넘김. refundId={}", label, refundId);
            case NOT_PROCESSING -> {
                // 되돌림을 받아 이미 실패로 끝낸 환불에 같은 되돌림이 다시 오는 것은 정상 중복이다
                if (kind == OrderRevertResultPolicy.Kind.REVERTED && recorded.refundStatus() == RefundStatus.FAILED) {
                    log.info("이미 실패로 끝난 환불의 주문 되돌림 메시지라 넘김. refundId={}", refundId);
                } else {
                    log.warn("처리 중이 아닌 환불의 주문 되돌리기 {} 메시지라 넘김(확인 필요). refundId={}, status={}, 주문 이유={}",
                            label, refundId, recorded.refundStatus(), orderReason);
                }
            }
            case NO_REVERT_STEP -> log.warn("되돌리기 줄이 없는 환불에 주문 되돌리기 {} 메시지가 와 넘김(있을 수 없는 상태, 확인 필요). refundId={}",
                    label, refundId);
            case ALREADY_FINISHED -> {
                // 이유까지 같아야 같은 실패의 정상 중복이다. 다른 이유(예: 늦은 지갑 완료로 우리가 멈춘 줄에 온 실패)는 사람이 봐야 해 WARN이다
                boolean sameResult = recorded.sameFailure();
                if (sameResult) {
                    log.info("되돌리기 줄이 이미 멈춰 주문 되돌리기 실패 메시지를 넘김. refundId={}", refundId);
                } else {
                    log.warn("되돌리기 줄이 이미 다른 결과로 끝나 처음 결과를 그대로 두고 넘김(주문 결과가 서로 다름, 확인 필요). refundId={}, stepStatus={}, 주문 이유={}",
                            refundId, recorded.stepStatus(), orderReason);
                }
            }
        }
    }

    // 주문이 반영한 뒤 주문 줄을 성공으로 바꾸고 다음 단계 줄(돈이 있으면 지갑, 0원이면 재고)을 준비한다(결정 5, #616 결정 14).
    // 다음 단계를 부르기 전에 이 구간을 커밋한다. 지갑 발행이 실패하거나 서버가 그 사이 죽어도 "어디까지 왔나"가 표에 남는다.
    // 단계 줄은 환불 행을 먼저 잠근 같은 구간에서만 고친다(결정 4, 잠금 순서 환불 행 → 단계 줄).
    // 주문 줄이 없으면 아무것도 쓰지 않는다. 승인 구간이 만드는 줄이라 없으면 있을 수 없는 상태다(#616 결정 13)
    private OrderRecorded recordOrderSucceeded(Long refundId, RefundStepType nextStep) {
        return transaction.execute(status -> {
            Refund refund = refundPersistencePort.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new IllegalStateException("주문 반영을 기록할 환불이 없습니다. refundId=" + refundId));
            RefundResult current = RefundResult.from(refund);
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                return new OrderRecorded(current, false, false);
            }
            Optional<RefundStep> orderStep = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER);
            if (orderStep.isEmpty()) {
                return new OrderRecorded(current, false, true);
            }
            if (orderStep.get().succeed()) {
                refundStepPersistencePort.update(orderStep.get());
            }
            StepPrep prep = prepareStep(refundId, nextStep, LocalDateTime.now(clock));
            return new OrderRecorded(current, prep == StepPrep.CALL, false);
        });
    }

    // 다른 모듈을 부르기 전에 그 단계 줄을 준비한다(#616 결정 14). 환불 행을 잠근 구간 안에서만 부른다.
    // 없음 → 시도 중으로 새로 만듦, 할 차례 → 다시 시작(시도 횟수 +1). 둘 다 CALL이다.
    // 시도 중·결과 대기는 누군가 하는 중이거나 답을 기다리는 중이라 다시 보내지 않고(BUSY), 끝 상태는 진행하지 않는다(ENDED).
    // 단, 만료된 시도 중(#576)과 기다리는 시각이 지난 결과 대기(#577)는 다시 부른다(CALL).
    // 확인 없이 새로 저장하면 같은 단계를 다시 진행할 때 (refund_id, step) 유일 제약에 걸린다
    private StepPrep prepareStep(Long refundId, RefundStepType type, LocalDateTime now) {
        return prepareStep(refundId, type, refundStepPersistencePort.findByRefundIdAndType(refundId, type), now);
    }

    // 같은 구간에서 이미 읽은 줄로 준비한다(같은 줄을 두 번 읽지 않으려고)
    private StepPrep prepareStep(Long refundId, RefundStepType type, Optional<RefundStep> existing, LocalDateTime now) {
        if (existing.isEmpty()) {
            refundStepPersistencePort.saveNew(RefundStep.start(refundId, type, now));
            return StepPrep.CALL;
        }
        RefundStep step = existing.get();
        if (step.begin(now)) {
            refundStepPersistencePort.update(step);
            return StepPrep.CALL;
        }
        // 시도 중인데 만료 시각이 지났으면 시도하던 쪽(죽은 서버, 실패 기록마저 실패한 요청)이 멈춘 것이라 다시 집는다(#576).
        // 잠근 구간 안에서 판단하므로 서버 여러 대가 같은 줄을 동시에 집지 않는다(먼저 집은 쪽이 만료 시각을 새로 잡는다).
        // 정상 겹침(BUSY, INFO)과 구분하려고 WARN으로 남긴다. 횟수를 다 썼으면 멈춘다
        if (step.restartExpired(now)) {
            refundStepPersistencePort.update(step);
            if (step.getStatus() == RefundStepStatus.STOPPED) {
                log.warn("시도 중 만료가 반복돼 횟수를 다 써 단계를 멈춤(확인 필요). refundId={}, step={}, attemptCount={}",
                        refundId, type, step.getAttemptCount());
                return StepPrep.ENDED;
            }
            // 이미 보낸 줄인데 처음 기다린 지 24시간이 지나 다시 집지 않고 늦은 결과만 기다리게 됐다(RefundStep.RESEND_LIMIT, #546)
            if (step.getStatus() == RefundStepStatus.AWAITING_RESULT) {
                log.warn("만료된 시도 중 줄이 다시 보내는 한도를 넘어 더 보내지 않고 늦은 결과만 기다림(확인 필요). refundId={}, step={}, awaitingSince={}",
                        refundId, type, step.getAwaitingSince());
                return StepPrep.BUSY;
            }
            log.warn("시도 중인 채로 만료된 단계 줄을 다시 집음. refundId={}, step={}, attemptCount={}",
                    refundId, type, step.getAttemptCount());
            return StepPrep.CALL;
        }
        // 결과 대기인데 기다리는 시각이 지났으면 결과 메시지가 늦거나 버려진 것이라 같은 refundId로 다시 보낸다(#577).
        // 잠근 구간 안에서 판단하므로 늦게 온 지갑 결과나 다른 서버와 겹쳐도 한 번만 시작한다(먼저 잠근 쪽이 줄을 바꾼다).
        // 시도 횟수에 넣지 않고 멈추지도 않는다(RefundStep.resendAfterResultTimeout).
        // INFO인 이유: 정해 둔 자동 회복 동작이고 많아(답이 끝내 안 오면 환불 1건에 24시간 동안 288줄, 그 뒤는 RESEND_LIMIT로 멈춤) WARN이면 다른 이상 신호를 덮는다.
        // 사람에게 알리는 일은 멈춘 환불 찾기(30분 넘게 기다림, WARN)가 한 시간에 한 줄로 맡는다.
        // 위의 만료 다시 집기는 5번에서 끝나 쌓이지 않으므로 WARN 그대로 둔다
        // 처음 기다린 지 24시간이 지났으면 더 보내지 않고 늦은 결과만 기다린다(RefundStep.RESEND_LIMIT, #546). 끝없이 보내 토픽이 쌓이지 않게 한다.
        // 한 번만 일어나 쌓이지 않으니 WARN으로 남긴다. 사람에게 알리는 일은 멈춘 환불 찾기가 이미 하고 있다
        if (step.stopResendingAfterLimit(now)) {
            refundStepPersistencePort.update(step);
            log.warn("결과를 처음 기다린 지 한도가 지나 같은 요청을 더 보내지 않고 늦은 결과만 기다림(확인 필요). refundId={}, step={}, awaitingSince={}",
                    refundId, type, step.getAwaitingSince());
            return StepPrep.BUSY;
        }
        if (step.resendAfterResultTimeout(now)) {
            refundStepPersistencePort.update(step);
            log.info("결과를 기다리는 시간이 지나 같은 요청을 다시 보냄. refundId={}, step={}, awaitingSince={}",
                    refundId, type, step.getAwaitingSince());
            return StepPrep.CALL;
        }
        return step.getStatus().isFinished() ? StepPrep.ENDED : StepPrep.BUSY;
    }

    // 다른 모듈에 보낼 값을 꺼낼 환불 행을 읽는다. 승인 구간이 커밋한 뒤라 없으면 코드 버그다
    private Refund readRefund(Long refundId) {
        return readOnlyTransaction.execute(status -> refundPersistencePort.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("승인 뒤 단계를 진행할 환불이 없습니다. refundId=" + refundId)));
    }

    // 지갑 발행이 끝나면 지갑 줄을 "결과 대기"로 바꾼다. 지갑 결과 메시지가 이 구간보다 먼저 와서 줄을 이미 끝냈으면(성공·멈춤)
    // 도메인이 false를 돌려주고 저장하지 않는다. 늦게 온 "결과 대기"가 끝난 결과를 덮어쓰지 않게 하려는 것이다(결정 2).
    // 이 구간이 실패하면 부르는 쪽이 WARN만 남기고 처리 중으로 응답한다. 발행은 이미 됐고 지갑 줄은 "시도 중"으로 남는다.
    // 회수 요청 발행 뒤(회수 줄, #546)도 같은 규칙이라 단계 종류를 받는다
    private void markAwaitingResult(Long refundId, RefundStepType type) {
        transaction.executeWithoutResult(status -> {
            Refund refund = refundPersistencePort.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new IllegalStateException("지갑 발행을 기록할 환불이 없습니다. refundId=" + refundId + ", step=" + type));
            // 그사이 처리 중에서 벗어났으면 쓰지 않는다(#616 결정 15). 지갑 완료 메시지가 이 기록보다 먼저 처리돼 완료된 것은
            // 정상 경쟁이라 INFO, 그 밖의 상태는 확인이 필요해 WARN이다
            // 주문 되돌리기(#622)는 주문이 빨리 답하면 이 기록보다 먼저 실패로 끝나는 것이 같은 정상 경쟁이라 INFO다
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                if (refund.getStatus() == RefundStatus.COMPLETED || refund.getStatus() == RefundStatus.FAILED) {
                    log.info("발행 뒤 결과가 먼저 처리돼 환불이 이미 끝나 결과 대기를 기록하지 않음. refundId={}, step={}, status={}",
                            refundId, type, refund.getStatus());
                } else {
                    log.warn("발행 뒤 환불이 처리 중이 아니라 결과 대기를 기록하지 않음. refundId={}, step={}, status={}",
                            refundId, type, refund.getStatus());
                }
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            Optional<RefundStep> sentStep = refundStepPersistencePort.findByRefundIdAndType(refundId, type);
            // 줄은 발행 전에 다른 구간이 만들어 커밋했으므로 없으면 버그다. 발행은 이미 됐으니
            // 응답은 바꾸지 않고 확인할 수 있게 WARN만 남긴다
            if (sentStep.isEmpty()) {
                log.warn("발행 뒤 결과 대기로 바꿀 단계 줄이 없음. refundId={}, step={}", refundId, type);
                return;
            }
            if (sentStep.get().awaitResult(now)) {
                refundStepPersistencePort.update(sentStep.get());
            }
        });
    }

    // 주문이 취소 반영을 거절하면(409) 처리 중이던 환불을 거절로 끝낸다. 자기 행만 잠그는 새 구간이다(완료와 같은 이유).
    // 사유는 출고 거절과 같은 문구를 쓴다. 가장 흔한 원인이 승인 사이의 출고이고, 구매자에게는 "취소할 수 없다"는 결과면 충분하다.
    // WARN으로 남기는 이유: 환불은 409의 원인을 구분하지 못한다. "이미 출고됨"은 드물게 있는 정상 경쟁이지만,
    // "수량 초과"나 "refundId 충돌"은 환불 쪽 버그다. 정상처럼 보이는 경로라도 사람이 확인하도록 모두 WARN으로 남긴다.
    // 예외 없이 넘기는 확인 필요한 이상이라 ERROR(처리되지 못한 예외)가 아니라 WARN이다(service.md 로그 표, #544에서 지갑 결과 로그와 맞춤).
    // 주문의 설명 문장은 로그에만 남기고 DB에는 저장하지 않는다. 예외를 던지지 않는 경로라 여기서 로그를 남긴다.
    // 로그는 거절 구간이 커밋된 뒤에 남긴다. 구간이 실패했는데 "거절로 끝냄"이 남으면 실제와 어긋나기 때문이다.
    // 한계: 처리 중 → 거절은 같은 묶음의 배송비 집계를 바꾸는데도 자기 행만 잠근다. 그 사이 같은 묶음의 다른 환불이 이 환불을
    // 취소된 것으로 보고 승인됐다면 그 환불의 배송비 판단이 틀린 채 남는다(RefundShippingFeePolicy.CANCELLING_STATUSES 주석, TODO(#707))
    // 잠근 뒤 처리 중이 아니면(그사이 다른 경로가 끝냄) 쓰지 않고 지금 상태를 돌려준다(#616 결정 15).
    // 주문 줄이 없어도 환불 거절은 한다. 주문이 반영하지 않았으니 끝내는 것만 남았고, 줄이 없는 것은 WARN으로 남긴다(#616 결정 13)
    private RefundResult rejectAfterOrderRefused(Long refundId, Long orderItemId, String orderDetail) {
        Closing closing = transaction.execute(status -> {
            Refund refund = refundPersistencePort.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new IllegalStateException(
                            "승인한 환불을 거절로 바꾸려는데 찾을 수 없습니다. refundId=" + refundId));
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                return new Closing(RefundResult.from(refund), false, true, false);
            }
            refund.rejectAfterApproval(Refund.SHIPPED_REJECT_REASON, LocalDateTime.now(clock));
            refundPersistencePort.update(refund);
            // 환불 거절과 주문 줄 "거절됨"을 같은 구간에서 함께 저장한다(결정 5). 따로 저장하면 하나만 남아 표와 환불이 어긋날 수 있다
            Optional<RefundStep> orderStep = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER);
            if (orderStep.isPresent() && orderStep.get().refuse(ORDER_REFUSED_REASON)) {
                refundStepPersistencePort.update(orderStep.get());
            }
            return new Closing(RefundResult.from(refund), true, true, orderStep.isEmpty());
        });
        if (!closing.changed()) {
            log.warn("주문이 취소 반영을 거절했지만 환불이 처리 중이 아니라 거절로 바꾸지 않음. refundId={}, status={}, orderDetail={}",
                    refundId, closing.result().status(), orderDetail);
            return closing.result();
        }
        log.warn("주문이 취소 반영을 거절해 환불을 거절로 끝냄. refundId={}, orderItemId={}, orderDetail={}",
                refundId, orderItemId, orderDetail);
        if (closing.stepMissing()) {
            log.warn("주문 거절로 끝낸 환불에 주문 단계 줄이 없음(있을 수 없는 상태, 확인 필요). refundId={}", refundId);
        }
        return closing.result();
    }

    // 재고 복구를 마친 환불을 완료로 바꾼다. 0원 승인과 지갑 완료 뒤가 같은 규칙을 쓴다(#616 결정 15).
    // 자기 행만 잠근다. 처리 중과 완료는 둘 다 배송비 판단에서 "이미 취소된 것"이라
    // (RefundShippingFeePolicy.CANCELLING_STATUSES) 바꿔도 같은 묶음의 집계가 변하지 않아 묶음 전체를 잠글 필요가 없다.
    // 같은 묶음의 다른 승인이 이 행을 먼저 잠갔으면 그 승인이 커밋할 때까지 기다린다. 이 행을 잠그는 첫 조회라 옛 값을 쓰지 않는다(새 트랜잭션).
    // 잠근 뒤 처리 중이 아니면(그사이 같은 메시지가 먼저 완료 등) 쓰지 않고 지금 상태를 돌려준다. 재고 줄이 없어도 완료는 한다.
    // 재고는 이미 되돌렸으니 끝내는 것만 남았고, 줄이 없는 것은 부르는 쪽이 WARN으로 남긴다(#616 결정 13)
    private Closing completeWithProductStep(Long refundId) {
        return transaction.execute(status -> {
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(refundId);
            if (locked.isEmpty()) {
                return new Closing(null, false, false, false);
            }
            Refund refund = locked.get();
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                return new Closing(RefundResult.from(refund), false, true, false);
            }
            refund.complete(LocalDateTime.now(clock));
            refundPersistencePort.update(refund);
            // 재고 줄 성공과 환불 완료를 같은 구간에서 함께 저장한다(결정 5). 따로 저장하면 하나만 남아 표와 환불이 어긋날 수 있다
            Optional<RefundStep> productStep = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.PRODUCT);
            if (productStep.isPresent() && productStep.get().succeed()) {
                refundStepPersistencePort.update(productStep.get());
            }
            return new Closing(RefundResult.from(refund), true, true, productStep.isEmpty());
        });
    }

    // 승인 요청 안에서 끝낸 결과를 로그로 남긴다. 구간이 커밋된 뒤에 부른다
    private static void logClosing(Closing closing, Long refundId, String what) {
        if (!closing.changed()) {
            log.warn("{} 하려는데 환불이 처리 중이 아니라 바꾸지 않고 지금 상태로 응답함. refundId={}, status={}",
                    what, refundId, closing.result().status());
            return;
        }
        if (closing.stepMissing()) {
            log.warn("{} 했는데 재고 단계 줄이 없음(있을 수 없는 상태, 확인 필요). refundId={}", what, refundId);
        }
    }

    // 정책이 넘기라고 한 이유를 로그로 남긴다(잠그기 전 판단).
    // 이미 완료된 환불의 완료 메시지는 같은 메시지가 두 번 온 정상(지갑은 첫 결과만 다시 보낸다)이라 INFO, 같은 실패가 이미 기록된 경우도 INFO.
    // 완료된 환불에 실패 메시지가 오면 중복이 아니라 앞선 완료 결과와 모순이므로 WARN으로 사람이 확인하게 한다(상태는 바꾸지 않는다).
    // 그 밖(요청됨·거절·철회, 없는 환불, 재고 단계가 시작된 환불)도 사람이 확인해야 하는 이상이라 WARN.
    // WARN인 이유: 예외 없이 넘기지만 사람이 확인해야 하는 이상이다(service.md 로그 표, ERROR는 처리되지 못한 예외)
    private static void logSkipped(WalletResultPolicy.SkipReason reason, WalletResultPolicy.Kind kind, Long refundId,
                                   Snapshot snapshot, String walletReason) {
        String label = labelOf(kind);
        switch (reason) {
            case REFUND_NOT_FOUND -> log.warn("지갑 반환 {} 메시지의 환불이 없어 넘김. refundId={}", label, refundId);
            case ALREADY_COMPLETED -> {
                if (kind == WalletResultPolicy.Kind.FAILED) {
                    log.warn("이미 완료된 환불에 지갑 반환 실패 메시지가 와 넘김(앞선 완료 결과와 모순, 확인 필요). refundId={}", refundId);
                } else {
                    log.info("이미 완료된 환불의 지갑 반환 {} 메시지라 넘김. refundId={}", label, refundId);
                }
            }
            case NOT_PROCESSING -> logSkippedNotProcessing(kind, refundId, snapshot, label);
            // 실패 메시지는 금액 비교보다 재고 단계 확인이 먼저라 "실패"로, 완료 메시지는 불일치일 때만 여기 오므로 "결과 불일치"로 남긴다
            case AFTER_WALLET_SUCCESS -> logIgnoredAfterWalletSuccess(snapshot.refund(), snapshot.productStep(),
                    kind == WalletResultPolicy.Kind.FAILED ? "실패" : "결과 불일치");
            case SAME_FAILURE -> log.info("같은 지갑 반환 실패가 이미 기록돼 넘김. refundId={}, reason={}", refundId, walletReason);
            // 지갑이 회수 요청을 먼저 받아 이 refundId의 반환을 막은 경우다(#546). 돈은 움직이지 않았고 회수 결과는 회수 줄에 따로 온다.
            // 지금 흐름에서는 지갑 완료 뒤에만 회수하므로 생기지 않아야 해서 사람이 확인하게 WARN이다
            case REFUND_REVERTED -> log.warn("지갑이 이미 회수한 refundId라 반환하지 않았다는 실패를 지갑 실패로 보지 않고 넘김(확인 필요). refundId={}",
                    refundId);
        }
    }

    // 처리 중이 아닌 환불의 지갑 결과를 넘길 때 남긴다(#622 투표). 실패로 끝난 환불의 지갑 완료는 경로에 따라 뜻이 다르다.
    // 회수 성공 경로(재고 줄 있음): 같은 완료가 다시 온 정상 중복이라 INFO.
    // 지갑 실패 경로(재고 줄 없음): 처음 결과가 실패였는데 완료가 온 약속 위반이라 돈이 나갔을 수 있다. 실패 환불은 찾기 알람에서 빠져 이 로그가 유일한 흔적이라 WARN
    private static void logSkippedNotProcessing(WalletResultPolicy.Kind kind, Long refundId, Snapshot snapshot, String label) {
        RefundStatus status = snapshot.refund().getStatus();
        if (status == RefundStatus.FAILED && kind == WalletResultPolicy.Kind.COMPLETED) {
            // 회수 경로인지는 재고 줄이 있는지로 본다(startProductStep과 같은 기준). 지갑 실패 경로에서는 재고 줄이 생기지 않는다
            if (snapshot.productStep() != null) {
                log.info("회수 뒤 실패로 끝난 환불에 같은 지갑 완료가 다시 와 넘김. refundId={}", refundId);
            } else {
                log.warn("지갑 실패로 되돌려 실패로 끝난 환불에 지갑 완료가 와 넘김(지갑 약속 위반, 돈이 나갔을 수 있음, 확인 필요). refundId={}",
                        refundId);
            }
            return;
        }
        log.warn("처리 중이 아닌 환불의 지갑 반환 {} 메시지라 넘김. refundId={}, status={}", label, refundId, status);
    }

    private static String labelOf(WalletResultPolicy.Kind kind) {
        return kind == WalletResultPolicy.Kind.FAILED ? "실패" : "완료";
    }

    // 잠근 뒤 다시 본 결과가 "지갑 완료가 이미 반영됨"인지. 그사이 완료됐거나 재고 단계가 시작된 경우라 앞선 지갑 완료와 모순(WARN)이다
    private static boolean isAfterWalletSuccess(WalletResultPolicy.Verdict verdict) {
        return verdict.decision() == WalletResultPolicy.Decision.SKIP
                && (verdict.skipReason() == WalletResultPolicy.SkipReason.AFTER_WALLET_SUCCESS
                || verdict.skipReason() == WalletResultPolicy.SkipReason.ALREADY_COMPLETED);
    }

    // 다음 할 일을 계획에 묻고, 이 자리에서 이어 가는 할 일과 다르면 코드 버그로 막는다. 순서 판단은 계획 한 곳에만 둔다(#575 결정 5)
    private static void requireNext(Refund refund, RefundSagaPlan.Event event, RefundSagaPlan.Action expected) {
        RefundSagaPlan.Action next = RefundSagaPlan.afterEvent(refund, event);
        if (next != expected) {
            throw new IllegalStateException("다음 할 일이 흐름과 다릅니다. refundId=" + refund.getId()
                    + ", event=" + event + ", 계획=" + next + ", 흐름=" + expected);
        }
    }

    // 거르는 판단에 쓸 값을 잠그지 않고 읽는다. 환불이 없으면 단계 줄도 읽지 않는다
    private Snapshot readSnapshot(Long refundId) {
        return readOnlyTransaction.execute(status -> {
            Refund refund = refundPersistencePort.findById(refundId).orElse(null);
            if (refund == null) {
                return new Snapshot(null, null, null);
            }
            return new Snapshot(refund, findProductStep(refundId),
                    refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET).orElse(null));
        });
    }

    // 재고 줄이 없으면 null
    private RefundStep findProductStep(Long refundId) {
        return refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.PRODUCT).orElse(null);
    }

    // 앞선 지갑 완료와 모순된 결과라 사람이 확인해야 해 WARN으로 남긴다(읽을 때 완료였던 환불에 실패 메시지가 온 경우와 같은 취급).
    // 잠근 구간 안에서 불러도 된다. 아무것도 쓰지 않고 넘기는 경우라 커밋 전후로 결과가 달라지지 않는다.
    // 재고 줄 상태를 함께 남겨 재고 단계가 어디까지 왔는지(시도 중·다시 시도 대기·성공·멈춤) 로그만으로 알 수 있게 한다
    private static void logIgnoredAfterWalletSuccess(Refund refund, RefundStep productStep, String what) {
        String productStepStatus = productStep == null ? "없음" : productStep.getStatus().name();
        log.warn("지갑 완료가 이미 반영된 환불(완료 또는 재고 단계가 시작됨)이라 지갑 반환 {}를 기록하지 않고 넘김(앞선 지갑 완료와 모순, 확인 필요). refundId={}, status={}, productStepStatus={}",
                what, refund.getId(), refund.getStatus(), productStepStatus);
    }

    // 지갑 줄을 멈춤으로 남긴다. 환불 행을 잠근 구간 안에서만 부른다.
    // 이미 멈춘 줄이면 처음 이유를 그대로 둔다(끝 상태는 바뀌지 않음, 결과 불일치 표시를 지키려고).
    // 줄이 없으면 만들지 않는다. 지갑에 보냈다면 발행 전에 만든 줄이 있어야 하므로 있을 수 없는 상태다(#616 결정 13). 부르는 쪽이 WARN으로 남긴다
    private MarkOutcome stopWalletStep(Optional<RefundStep> walletStep, String reason) {
        if (walletStep.isEmpty()) {
            return MarkOutcome.NO_WALLET_STEP;
        }
        if (walletStep.get().stop(reason)) {
            refundStepPersistencePort.update(walletStep.get());
            return MarkOutcome.MARKED;
        }
        return MarkOutcome.ALREADY_STOPPED;
    }

    // 지갑 결과를 남길 지갑 줄이 없는 경우. 기록 대신 WARN으로 남겨 사람이 보게 한다. 환불은 처리 중으로 남고 멈춘 환불 찾기(#577)가 찾는다
    private static void logNoWalletStep(Long refundId, String reason) {
        log.warn("지갑 결과를 남길 지갑 단계 줄이 없어 기록하지 않고 넘김(있을 수 없는 상태, 확인 필요). refundId={}, 이유={}", refundId, reason);
    }

    // 뒤에 온 이유는 기록하지 않지만 사람이 볼 수 있게 WARN으로 남긴다(지갑이 같은 환불에 서로 다른 결과를 보낸 이상)
    private static void logKeptFirstStopReason(Long refundId, String newReason) {
        log.warn("지갑 줄이 이미 다른 이유로 멈춰 있어 처음 이유를 그대로 두고 넘김(지갑 결과가 서로 다름, 확인 필요). refundId={}, 새 이유={}",
                refundId, newReason);
    }

    // 금액·주문·구매자가 다른 완료·실패 메시지를 불일치로 기록한다. 완료도 재고 복구도 하지 않는다.
    // 두 메시지가 같은 규칙을 따르게 한 곳에서 기록한다(한쪽만 고쳐 동작이 갈라지지 않게).
    // walletReason은 실패 메시지가 알려 준 이유다(완료 메시지면 null). 기록은 불일치로 덮이므로 로그로 남긴다
    // 잠그기 전 판단(재고 단계가 시작됐으면 넘김)은 정책이 이미 했다. 잠근 뒤 같은 정책으로 다시 본다
    private void recordMismatch(WalletResultPolicy.Kind kind, Long refundId, Long messageOrderId, Long messageBuyerId,
                                long messageAmount, String walletReason, Refund refund) {
        MarkOutcome outcome = transaction.execute(status -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElse(null);
            Optional<RefundStep> walletStep = locked == null ? Optional.empty()
                    : refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET);
            RefundStep productStep = locked == null ? null : findProductStep(refundId);
            WalletResultPolicy.Verdict again = WalletResultPolicy.judge(kind, locked, walletStep.orElse(null), productStep,
                    messageOrderId, messageBuyerId, messageAmount, walletReason);
            if (isAfterWalletSuccess(again)) {
                logIgnoredAfterWalletSuccess(locked, productStep, "결과 불일치");
                return MarkOutcome.AFTER_WALLET_SUCCESS;
            }
            if (again.decision() != WalletResultPolicy.Decision.MISMATCH) {
                return MarkOutcome.CHANGED;
            }
            // 환불 행은 바꾸지 않는다(불일치는 지갑 줄에만 남는다, 결정 3). 환불 행은 단계 줄을 지키려고 잠근 것이다
            return stopWalletStep(walletStep, RESULT_MISMATCH);
        });
        if (outcome == MarkOutcome.AFTER_WALLET_SUCCESS) {
            return;
        }
        if (outcome == MarkOutcome.ALREADY_STOPPED) {
            logKeptFirstStopReason(refundId, RESULT_MISMATCH);
            return;
        }
        if (outcome == MarkOutcome.NO_WALLET_STEP) {
            logNoWalletStep(refundId, RESULT_MISMATCH);
            return;
        }
        if (outcome != MarkOutcome.MARKED) {
            log.info("지갑 반환 결과 불일치를 기록하려는 사이 환불이 바뀌어 넘김. refundId={}", refundId);
            return;
        }
        log.warn("지갑 반환 결과가 환불과 달라 불일치로 기록함(처리 중 유지). refundId={}, 환불 orderId={}, 메시지 orderId={}, "
                        + "환불 buyerId={}, 메시지 buyerId={}, 환불 금액={}, 메시지 금액={}, 지갑 이유={}",
                refundId, refund.getOrderId(), messageOrderId, refund.getBuyerId(), messageBuyerId,
                refund.getRefundAmount(), messageAmount, walletReason);
    }

    // 지갑 완료를 표에 반영하고 재고 줄을 준비한 뒤 커밋한다. 재고를 부르기 전에 커밋해 서버가 그 사이 죽어도 "어디까지 왔나"가 남는다(결정 5).
    // 지갑 줄: 없으면 아무것도 쓰지 않는다. 지갑에 보냈다면 발행 전에 만든 줄이 있어야 하므로 있을 수 없는 상태다(#616 결정 13).
    // 앞서 실패·불일치로 멈춰 있으면 멈춤 그대로 둔다(끝난 줄은 바뀌지 않는다. 지갑이 실패를 알렸던 기록을 남기려는 것, #544 결정 8). 환불은 그래도 완료로 간다.
    // 재고 줄: 없으면 만들고, 할 차례(앞선 재고 실패)면 다시 시작한다. 시도 중·결과 대기면 누군가 하는 중이라 다시 부르지 않고,
    // 끝 상태(예: 마이그레이션이 옮긴 옛 멈춤 줄)면 진행하지 않는다(#616 결정 14).
    // 잠근 뒤 처리 중이 아니면(그사이 같은 메시지가 먼저 완료 등) 아무것도 쓰지 않는다
    private ProductStart startProductStep(Long refundId, WalletRefundCompletedCommand command) {
        return transaction.execute(status -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElse(null);
            Optional<RefundStep> walletStep = locked == null ? Optional.empty()
                    : refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET);
            RefundStep productStep = locked == null ? null : findProductStep(refundId);
            WalletResultPolicy.Verdict again = WalletResultPolicy.judge(WalletResultPolicy.Kind.COMPLETED, locked,
                    walletStep.orElse(null), productStep, command.orderId(), command.buyerId(), command.amount(), null);
            if (again.decision() != WalletResultPolicy.Decision.ACCEPT) {
                return ProductStart.CHANGED;
            }
            if (walletStep.isEmpty()) {
                return ProductStart.NO_WALLET_STEP;
            }
            // 주문 되돌리기를 시작한 환불은 지갑 완료가 와도 재고로 가지 않는다(#622 검토). 되돌리기 줄은 두 경로(지갑 실패, 회수 성공)에서 생긴다.
            // 경로는 재고 줄이 있는지로 가른다(2라운드 재검). 지갑 줄 상태로 가르면 앞선 불일치로 지갑 줄이 멈춘 채 맞는 완료를 받아 재고로 간 회수 경로(#544 결정 8)를 지갑 실패로 잘못 본다.
            // 회수 성공 경로(재고 줄 있음): 같은 완료가 다시 온 정상 중복이라 아무것도 쓰지 않는다. 여기서 되돌리기를 멈추면 끝날 환불이 처리 중에 갇힌다(투표 지적).
            // 지갑 실패 경로(재고 줄 없음, 지갑 실패는 재고 줄이 있으면 받지 않는다): 처음 결과가 실패인데 완료가 온 지갑 약속 위반이다. 재고·완료까지 가면 상품 배송과 돈 반환이 함께 일어나 되돌릴 수 없다.
            // 되돌리기 줄이 끝나기 전이면 이유를 남기고 멈춘다. 그러면 주문 결과가 와도 실패로 끝나지 않고 처리 중으로 남아, 찾기 알람이 사람에게 알린다
            // (멈추지 않으면 주문이 되돌린 뒤 FAILED가 되어 알람에서 빠지고 DB에 흔적이 없다). 같은 구간에서 읽은 줄 객체로 고친다(4절)
            Optional<RefundStep> orderRevert = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER_REVERT);
            if (orderRevert.isPresent()) {
                if (productStep != null) {
                    return ProductStart.DUPLICATE_AFTER_REVERT;
                }
                // 되돌리기 줄이 이미 다른 이유(예: 이미 구매확정)로 끝났으면 고치지 않는다(끝 상태). 로그만 다르게 남긴다
                if (orderRevert.get().stop(WALLET_COMPLETED_LATE)) {
                    refundStepPersistencePort.update(orderRevert.get());
                    return ProductStart.WALLET_COMPLETED_LATE;
                }
                return ProductStart.WALLET_COMPLETED_LATE_AFTER_REVERT_ENDED;
            }
            if (walletStep.get().succeed()) {
                refundStepPersistencePort.update(walletStep.get());
            }
            StepPrep prep = prepareStep(refundId, RefundStepType.PRODUCT, Optional.ofNullable(productStep), LocalDateTime.now(clock));
            return switch (prep) {
                case CALL -> ProductStart.STARTED;
                case BUSY -> ProductStart.PRODUCT_BUSY;
                case ENDED -> ProductStart.PRODUCT_ENDED;
            };
        });
    }

    // 재고 단계를 시작하지 않은 이유를 남긴다. 구간이 커밋된 뒤에 부른다.
    // 바뀜(그사이 완료 등)과 진행 중(같은 메시지가 동시에 처리 중)은 정상 중복이라 INFO, 있을 수 없는 상태와 끝 상태는 사람이 볼 일이라 WARN.
    // 진행 중이 오래 멈춘 줄(서버가 죽음)은 지휘 작업(#576)이 다시 집는다
    private static void logProductNotStarted(ProductStart started, Long refundId) {
        switch (started) {
            case CHANGED -> log.info("재고 복구를 시작하려는 사이 환불이 바뀌어 넘김. refundId={}", refundId);
            case PRODUCT_BUSY -> log.info("재고 단계가 이미 시도 중이라 다시 부르지 않고 넘김. refundId={}", refundId);
            case NO_WALLET_STEP -> log.warn("지갑 완료를 받았지만 지갑 단계 줄이 없어 재고를 부르지 않음(있을 수 없는 상태, 확인 필요). refundId={}", refundId);
            case PRODUCT_ENDED -> log.warn("지갑 완료를 받았지만 재고 단계 줄이 끝 상태라 재고를 부르지 않음(확인 필요). refundId={}", refundId);
            case DUPLICATE_AFTER_REVERT -> log.info("회수 뒤 주문 되돌리기 중인 환불에 같은 지갑 완료가 다시 와 넘김. refundId={}", refundId);
            case WALLET_COMPLETED_LATE -> log.warn("지갑 실패로 주문 되돌리기를 시작한 환불에 같은 refundId의 지갑 완료가 와 재고를 부르지 않고 되돌리기 줄을 멈춤"
                    + "(지갑 약속 위반, 돈이 나갔을 수 있음. 주문·돈을 사람이 맞춰야 함, 확인 필요). refundId={}", refundId);
            case WALLET_COMPLETED_LATE_AFTER_REVERT_ENDED -> log.warn("지갑 실패로 주문 되돌리기를 시작한 환불에 같은 refundId의 지갑 완료가 와 재고를 부르지 않음. 되돌리기 줄은 이미 끝나 그대로 둠"
                    + "(지갑 약속 위반, 돈이 나갔을 수 있음. 주문·돈을 사람이 맞춰야 함, 확인 필요). refundId={}", refundId);
            case STARTED -> throw new IllegalArgumentException("시작한 경우는 넘기지 않는다");
        }
    }

    /**
     * 다른 모듈 호출 한 단계를 실행해 결과를 돌려준다. 실패하면 그 단계 줄에 이유를 기록한 뒤 원래 예외를 그대로 던진다(#574).
     * 기록마저 실패하면 기록 실패를 원래 예외에 붙여(suppressed) 원래 예외를 던진다.
     * {@link Error}(메모리 부족, 무한 호출 등)는 일부러 잡지 않아 기록하지 않는다. JVM이 망가진 상태라 DB 기록도 믿을 수 없고
     * 기록하려다 상황을 더 나쁘게 만들 수 있어, 그대로 올려 서버 오류로 드러내는 편이 낫다(만일을 대비해 밝혀 둔다).
     * 트랜잭션 밖에서 불린다는 전제는 입구(공개 메서드)가 지킨다. 이 메서드는 입구 안에서만 부른다.
     * 패키지 안에서만 연다: 기록과 이유 이름 규칙을 호출 하나로 따로 확인하는 단위 테스트가 직접 부른다.
     *
     * @param call 다른 모듈 호출. 돌려줄 값이 없으면 {@code null}을 돌려준다
     */
    <T> T runStep(Long refundId, RefundStepType step, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            Refund revertTarget = recordFailureKeepingCause(refundId, step, e);
            // 재고 거절로 회수 줄을 만들었으면 기록이 커밋된 뒤 바로 Kafka로 회수를 요청한다(#546).
            // 재고 거절이 나는 두 입구(지갑 완료 수신, 알람)가 모두 이 틀을 거치므로 보내는 코드는 여기 한 곳이다
            if (revertTarget != null) {
                sendWalletRevertRightAway(refundId, revertTarget);
            }
            throw e;
        }
    }

    // 회수 요청을 바로 보낸다. 실패해도 원래 재고 실패를 그대로 올린다(부르는 쪽 응답·로그는 재고 실패 기준이라).
    // 계획 확인이 어긋나면 코드 버그라 ERROR로 남긴다. 발행 실패와 한데 묶어 WARN으로 남기면 버그가 묻힌다(봇 리뷰).
    // 발행 실패는 회수 줄에 이미 기록됐다(할 차례 → 알람이 다시 보냄, 5번 다 쓰면 멈춤). 이쪽은 정해 둔 회복이라 WARN이다
    private void sendWalletRevertRightAway(Long refundId, Refund refund) {
        try {
            requireNext(refund, RefundSagaPlan.Event.WALLET_REVERT_PUBLISHED, RefundSagaPlan.Action.WAIT);
        } catch (IllegalStateException planBug) {
            log.error("회수 요청 전 계획 확인이 어긋나 보내지 않음(코드 버그, 회수 줄은 시도 중으로 남아 만료 뒤 알람이 다시 집음). refundId={}",
                    refundId, planBug);
            return;
        }
        try {
            publishWalletRevert(refundId, refund);
        } catch (RuntimeException sendError) {
            log.warn("재고 거절 뒤 회수 요청을 바로 보내지 못함. 회수 줄에 기록했고 알람이 다시 보냄. refundId={}", refundId, sendError);
        }
    }

    // 실패 기록은 바깥 트랜잭션 없이 따로 커밋돼야 한다. 바깥 트랜잭션 안에서 부르면 기록이 거기에 합류해,
    // 이어서 던지는 원래 예외로 바깥이 롤백될 때 기록도 함께 사라진다. 기록 자체는 성공했으니 ERROR 로그도 남지 않아 아무도 모른다.
    // 그래서 입구에서 다른 모듈을 부르기 전에 막는다. 승인 서비스는 승인 구간을 커밋한 뒤 부르고, Kafka 수신기는 트랜잭션 없이 부르므로
    // 정상 경로에서는 걸리지 않는다
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("환불 승인 뒤 단계는 트랜잭션 밖에서 불러야 합니다. 실패 기록이 바깥 트랜잭션과 함께 롤백됩니다.");
        }
    }

    // 응답은 원래 예외로 한다. 사용자에게 필요한 건 "지갑이 응답하지 않았다" 같은 진짜 원인이고,
    // 기록 실패(DB 순간 장애 등)로 응답을 바꾸면 그 원인이 가려진다.
    // 대신 기록이 없으면 처리 중인 건과 멈춘 건이 구분되지 않아 운영자가 놓치므로, 기록 실패는 원래 예외에 붙이고 ERROR로 남긴다.
    // 원래 예외가 RestApiException(*_API_FAILED 등)이면 ApiControllerAdvice가 응답만 만들고 로그를 남기지 않아, 이 로그가 기록 실패의 유일한 흔적이다.
    // 원래 예외가 그 밖의 예외면 advice가 suppressed까지 함께 남겨 기록 실패가 두 번 찍히지만, 드문 경우라 놓치지 않는 쪽을 택한다
    // 회수 줄을 만들었으면 회수 요청에 쓸 환불(잠근 구간에서 읽은 값)을 돌려준다. 아니거나 기록이 실패하면 null
    private Refund recordFailureKeepingCause(Long refundId, RefundStepType step, RuntimeException cause) {
        String failureCode = failureCodeOf(cause);
        try {
            return recordFailure(refundId, step, failureCode);
        } catch (RuntimeException recordError) {
            cause.addSuppressed(recordError);
            log.error("환불 실패 단계 기록 실패. 멈춘 단계가 단계 줄에 남지 않음. refundId={}, step={}, failureCode={}",
                    refundId, step, failureCode, recordError);
            return null;
        }
    }

    /**
     * 다른 모듈 호출이 실패한 단계의 진행 줄에 이유를 새 트랜잭션 구간에서 남긴다. 환불 상태는 처리 중 그대로 둔다.
     * 다른 모듈 호출은 트랜잭션 밖에서 하므로 이 기록은 호출과 따로 저장되고, 호출이 실패해도 기록은 남는다.
     * 진행 줄은 호출 전에 만들어 두므로 여기서 만들지 않는다.
     * 패키지 안에서만 연다: {@link #runStep} 안에서 쓰고, 칸 길이 같은 저장 조건을 실제 DB로 확인하는 테스트가 직접 부른다.
     *
     * 처리 중이 아니면(그사이 완료·거절 등) 기록하지 않고 WARN만 남긴다(#616 결정 15). 끝난 환불에 실패를 남기면 멈춘 것처럼 보이기 때문이다.
     *
     * 재고가 다시 해도 같은 이유로 거절되면 같은 구간에서 회수 줄을 시도 중으로 만든다(#546). 보내기는 커밋 뒤 부르는 쪽이 한다.
     *
     * @param failureCode 에러 코드 이름, 없으면 예외 클래스 이름
     * @return 회수 줄을 만들었으면 회수 요청 값을 꺼낼 환불, 아니면 {@code null}
     * @throws IllegalStateException 환불이나 그 단계의 진행 줄이 없으면 (부르는 쪽 버그)
     */
    Refund recordFailure(Long refundId, RefundStepType step, String failureCode) {
        return transaction.execute(status -> {
            // 같은 환불을 동시에 결정·기록하는 다른 트랜잭션과 섞이지 않게 다른 결정처럼 잠근 뒤 고친다.
            // refundId는 사용자가 아니라 승인을 마친 시스템이 넘긴 값이라, 없으면 사용자 잘못이 아닌 코드 버그로 보고 표준 예외(500)로 던진다(exception.md)
            Refund refund = refundPersistencePort.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new IllegalStateException("실패를 기록할 환불이 없습니다. refundId=" + refundId));
            // 환불 행은 고치지 않지만 잠근다. 단계 줄은 따로 잠그지 않고 환불 행 잠금으로 지키기 때문이다(결정 4, 잠금 순서: 환불 행 → 단계 줄)
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                log.warn("다른 모듈 호출이 실패했지만 환불이 처리 중이 아니라 실패를 기록하지 않음. refundId={}, step={}, status={}, failureCode={}",
                        refundId, step, refund.getStatus(), failureCode);
                return null;
            }
            // 다른 모듈을 부르기 전에 진행 줄을 만들어 커밋한다(결정 5). 없다면 그 순서를 어긴 코드 버그라 표준 예외로 던진다.
            // 이 예외는 runStep에서 원래 예외에 붙고 ERROR 로그로 남는다
            RefundStep stepRow = refundStepPersistencePort.findByRefundIdAndType(refundId, step)
                    .orElseThrow(() -> new IllegalStateException(
                            "실패를 기록할 단계 줄이 없습니다. refundId=" + refundId + ", step=" + step));
            // 다시 해도 결과가 같은 실패는 다시 시도하지 않고 바로 멈춘다(#576)
            LocalDateTime now = LocalDateTime.now(clock);
            boolean nonRetryable = NON_RETRYABLE_FAILURES.contains(failureCode);
            boolean changed = nonRetryable ? stepRow.stop(failureCode) : stepRow.failForRetry(failureCode, now);
            if (!changed) {
                return null;
            }
            refundStepPersistencePort.update(stepRow);
            if (stepRow.getStatus() != RefundStepStatus.STOPPED) {
                return null;
            }
            log.warn("다른 모듈 호출 실패로 단계를 멈춤(다시 해도 같은 실패이거나 시도 횟수를 다 씀, 확인 필요). refundId={}, step={}, failureCode={}, attemptCount={}",
                    refundId, step, failureCode, stepRow.getAttemptCount());
            // 재고가 다시 해도 같은 이유로 거절되면 돌려준 돈을 다시 가져온다(#546). 5번을 다 써서 멈춘 재고(일시 장애)는 회수하지 않는다(11/16 결정):
            // 몇 분짜리 장애만으로 돈을 가져가지 않으려고 멈춘 상태가 아니라 이유로 가른다
            if (step == RefundStepType.PRODUCT && WALLET_REVERT_TRIGGER.equals(failureCode) && startWalletRevert(refundId, refund, now)) {
                return refund;
            }
            return null;
        });
    }

    // 회수 줄을 시도 중으로 만든다(#546). 재고 줄을 멈춘 같은 구간에서 만들어, 재고 멈춤만 남고 회수 줄이 빠지는 일이 없게 한다.
    // 발행은 이 구간이 커밋된 뒤 runStep이 바로 한다(먼저 기록하고 보내기). 시도 중으로 만드는 이유: 보내는 동안 알람이 같은 줄을 또 집지 않게 한다.
    // 보내다 서버가 죽으면 2분 뒤 만료돼 알람이 다시 집는다(#576).
    // 돌려준 돈이 없으면(0원) 가져올 것이 없어 만들지 않는다(계획이 정함). 회수 줄이 이미 있으면 다시 만들지 않는다((refund_id, step) 유일 제약)
    private boolean startWalletRevert(Long refundId, Refund refund, LocalDateTime now) {
        if (RefundSagaPlan.afterEvent(refund, RefundSagaPlan.Event.PRODUCT_REFUSED) != RefundSagaPlan.Action.CALL_WALLET_REVERT) {
            log.warn("재고가 다시 해도 거절됐지만 돌려준 돈이 없어 회수하지 않음. 주문도 되돌리지 않고 사람이 봄(#622 결정 D1, 확인 필요). refundId={}", refundId);
            return false;
        }
        if (refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET_REVERT).isPresent()) {
            return false;
        }
        refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.WALLET_REVERT, now));
        log.warn("재고가 다시 해도 거절돼 지갑 돈 다시 가져오기를 시작함(확인 필요). refundId={}, amount={}", refundId, refund.getRefundAmount());
        return true;
    }

    // 예외 메시지에는 다른 모듈의 응답 내용이 들어갈 수 있어 DB에 남기지 않고, 에러 코드 이름이나 예외 클래스 이름만 쓴다
    private static String failureCodeOf(RuntimeException e) {
        if (e instanceof RestApiException restApiException) {
            return restApiException.getErrorCode().name();
        }
        String name = e.getClass().getSimpleName();
        // 만일을 대비한 처리: 그 자리에서 만든 이름 없는 예외(new RuntimeException() { })는 짧은 이름이 빈 값이라,
        // 그대로 넘기면 RefundStep.failForRetry가 "이유가 비었다"며 거부해 진짜 실패가 기록되지 않는다. 지금 환불 코드에는 없지만
        // 이 틀로 부르는 호출(지금은 주문 반영·지갑 발행·재고 복구)이 늘어날 때를 위해 패키지까지 붙은 이름으로 바꾼다
        if (name.isEmpty()) {
            name = e.getClass().getName();
        }
        // 패키지까지 붙은 이름은 길 수 있다. 칸(last_reason VARCHAR(100))을 넘으면 저장이 실패해 역시 기록이 사라지므로,
        // 어느 클래스인지 알아볼 수 있는 앞부분만 남긴다. 길이 상한은 이유를 저장하는 도메인(RefundStep)의 것을 쓴다
        if (name.length() > RefundStep.MAX_REASON_LENGTH) {
            name = name.substring(0, RefundStep.MAX_REASON_LENGTH);
        }
        return name;
    }
}
