package com.ok.refund.application.service;

import com.ok.refund.application.port.in.HandleWalletRefundResultUseCase;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.domain.RefundStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 지갑의 예치금 반환 결과(완료·실패)를 환불에 반영한다.
 * 완료는 읽어서 거르기(구간 밖) → 재고 복구(구간 밖, {@link RefundStepRunner}) → 자기 행을 잠그고 완료로 바꾸기(구간)로 나뉜다.
 * 실패는 읽어서 거르기 → 자기 행을 잠그고 실패 기록(구간)이다.
 * 걸러 넘기는 경우는 예외를 던지지 않는다. 던지면 메시지 오류 처리기가 같은 메시지를 다시 시도해 줄만 막힌다.
 * 잠그지 않고 먼저 읽는 이유: 재고 복구(상품 HTTP) 동안 행 잠금을 쥐지 않으려는 것이고,
 * 읽은 뒤 잠그기 전에 상태가 바뀔 수 있어 잠근 뒤 상태를 다시 확인한다.
 */
@Slf4j
@Service
public class HandleWalletRefundResultService implements HandleWalletRefundResultUseCase {

    // 지갑이 알려 준 금액·주문이 환불과 다를 때 failureCode에 남기는 값. 지갑의 reason과 겹치지 않게 대문자 상수로 둔다
    static final String RESULT_MISMATCH = "RESULT_MISMATCH";

    private final RefundPersistencePort refundPersistencePort;
    private final ProductStockReleasePort productStockReleasePort;
    private final RefundStepRunner refundStepRunner;
    private final Clock clock;
    // 거르기 위한 읽기용. 잠그지 않는다
    private final TransactionTemplate readOnlyTransaction;
    // 자기 행을 잠그고 바꾸는 구간
    private final TransactionTemplate transaction;

    public HandleWalletRefundResultService(RefundPersistencePort refundPersistencePort,
                                           ProductStockReleasePort productStockReleasePort,
                                           RefundStepRunner refundStepRunner, Clock clock,
                                           PlatformTransactionManager transactionManager) {
        this.refundPersistencePort = refundPersistencePort;
        this.productStockReleasePort = productStockReleasePort;
        this.refundStepRunner = refundStepRunner;
        this.clock = clock;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void completeAfterWalletRefund(WalletRefundCompletedCommand command) {
        Long refundId = command.refundId();
        Refund refund = readOnlyTransaction.execute(status -> refundPersistencePort.findById(refundId).orElse(null));
        if (!isProcessingOrLog(refund, refundId, "완료")) {
            return;
        }
        // 지갑이 돌려준 금액·주문이 환불이 요청한 것과 다르면 완료하지 않는다. 돈이 어긋난 채 끝내면 되돌릴 방법이 없어 사람이 보게 한다
        if (refund.getRefundAmount() != command.amount() || !refund.getOrderId().equals(command.orderId())) {
            recordMismatch(command, refund);
            return;
        }
        // 재고 복구는 구간 밖. 상품은 같은 refundId면 또 풀지 않아 같은 메시지가 다시 와도 안전하다.
        // 실패하면 틀이 PRODUCT 실패를 기록하고 예외를 다시 던진다. 오류 처리기가 다시 시도한다
        refundStepRunner.run(refundId, RefundFailedStep.PRODUCT, () -> {
            productStockReleasePort.releaseStock(new StockRelease(
                    refundId, refund.getOrderId(), refund.getProductOptionId(), refund.getQuantity()));
            return null;
        });
        complete(refundId);
    }

    @Override
    public void recordWalletRefundFailure(WalletRefundFailedCommand command) {
        Long refundId = command.refundId();
        Refund refund = readOnlyTransaction.execute(status -> refundPersistencePort.findById(refundId).orElse(null));
        if (!isProcessingOrLog(refund, refundId, "실패")) {
            return;
        }
        if (isSameFailure(refund, command.reason())) {
            log.info("같은 지갑 반환 실패가 이미 기록돼 넘김. refundId={}, reason={}", refundId, command.reason());
            return;
        }
        boolean recorded = Boolean.TRUE.equals(transaction.execute(status -> {
            // 읽은 뒤 잠그기 전에 상태나 기록이 바뀔 수 있어 잠근 뒤 다시 본다. 이 행을 잠그는 첫 조회라 옛 값을 쓰지 않는다
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(refundId);
            if (locked.isEmpty() || locked.get().getStatus() != RefundStatus.PROCESSING
                    || isSameFailure(locked.get(), command.reason())) {
                return false;
            }
            locked.get().recordFailure(RefundFailedStep.WALLET, command.reason());
            refundPersistencePort.update(locked.get());
            return true;
        }));
        if (!recorded) {
            log.info("지갑 반환 실패를 기록하려는 사이 환불이 바뀌어 넘김. refundId={}", refundId);
            return;
        }
        // 구간이 커밋된 뒤에 남긴다. 구간이 실패했는데 "기록함"이 남으면 실제와 어긋난다
        log.error("지갑 반환 실패를 환불에 기록함(처리 중 유지). refundId={}, orderId={}, amount={}, reason={}",
                refundId, command.orderId(), command.amount(), command.reason());
    }

    // 없는 환불이나 처리 중이 아닌 환불이면 이유를 남기고 false. 이미 완료된 환불은 같은 메시지가 두 번 온 정상이라 INFO,
    // 그 밖(요청됨·거절·철회, 없는 환불)은 지갑이 돈을 움직인 환불이 이 상태일 수 없어 ERROR로 사람이 확인하게 한다
    private boolean isProcessingOrLog(Refund refund, Long refundId, String kind) {
        if (refund == null) {
            log.error("지갑 반환 {} 메시지의 환불이 없어 넘김. refundId={}", kind, refundId);
            return false;
        }
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            log.info("이미 완료된 환불의 지갑 반환 {} 메시지라 넘김. refundId={}", kind, refundId);
            return false;
        }
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            log.error("처리 중이 아닌 환불의 지갑 반환 {} 메시지라 넘김. refundId={}, status={}", kind, refundId, refund.getStatus());
            return false;
        }
        return true;
    }

    private static boolean isSameFailure(Refund refund, String reason) {
        return refund.getStatus() == RefundStatus.PROCESSING
                && refund.getFailedStep() == RefundFailedStep.WALLET
                && reason.equals(refund.getFailureCode());
    }

    // 금액·주문이 다른 완료 메시지를 실패로 기록한다. 완료도 재고 복구도 하지 않는다
    private void recordMismatch(WalletRefundCompletedCommand command, Refund refund) {
        boolean recorded = Boolean.TRUE.equals(transaction.execute(status -> {
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(command.refundId());
            if (locked.isEmpty() || locked.get().getStatus() != RefundStatus.PROCESSING) {
                return false;
            }
            locked.get().recordFailure(RefundFailedStep.WALLET, RESULT_MISMATCH);
            refundPersistencePort.update(locked.get());
            return true;
        }));
        if (!recorded) {
            log.info("지갑 반환 결과 불일치를 기록하려는 사이 환불이 바뀌어 넘김. refundId={}", command.refundId());
            return;
        }
        log.error("지갑 반환 결과가 환불과 달라 완료하지 않음. refundId={}, 환불 orderId={}, 메시지 orderId={}, 환불 금액={}, 메시지 금액={}",
                command.refundId(), refund.getOrderId(), command.orderId(), refund.getRefundAmount(), command.amount());
    }

    // 재고까지 마친 환불을 완료로 바꾼다. 자기 행만 잠그고, 잠근 뒤 상태를 다시 본다(그 사이 같은 메시지가 먼저 완료했을 수 있다).
    // DecideRefundService의 같은 이름 메서드와 같은 구조를 일부러 다시 쓴다. 서비스 패키지에 공용 도우미를 두지 않는 모듈 규칙이다(3절)
    private void complete(Long refundId) {
        boolean completed = Boolean.TRUE.equals(transaction.execute(status -> {
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(refundId);
            if (locked.isEmpty() || locked.get().getStatus() != RefundStatus.PROCESSING) {
                return false;
            }
            locked.get().complete(LocalDateTime.now(clock));
            refundPersistencePort.update(locked.get());
            return true;
        }));
        if (!completed) {
            log.info("재고 복구를 마친 사이 환불이 이미 바뀌어 완료하지 않고 넘김. refundId={}", refundId);
        }
    }
}
