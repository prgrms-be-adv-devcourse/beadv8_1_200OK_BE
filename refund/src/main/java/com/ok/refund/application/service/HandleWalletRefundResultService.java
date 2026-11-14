package com.ok.refund.application.service;

import com.ok.refund.application.port.in.HandleWalletRefundResultUseCase;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepType;
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
 * 완료는 읽어서 거르기(읽기 구간) → 자기 행을 잠그고 지갑 줄 성공·재고 줄 시작(구간) → 재고 복구(구간 밖, {@link RefundStepRunner})
 * → 자기 행을 잠그고 재고 줄 성공과 완료(구간)로 나뉜다.
 * 실패는 읽어서 거르기 → 자기 행을 잠그고 지갑 줄을 멈춤으로 기록(구간)이다. 거르는 판단은 단계 진행 표를 본다(#574).
 * 걸러 넘기는 경우는 예외를 던지지 않는다. 던지면 메시지 오류 처리기가 같은 메시지를 다시 시도해 줄만 막힌다.
 * 잠그지 않고 먼저 읽는 이유: 재고 복구(상품 HTTP) 동안 행 잠금을 쥐지 않으려는 것이고,
 * 읽은 뒤 잠그기 전에 상태가 바뀔 수 있어 잠근 뒤 상태를 다시 확인한다.
 */
@Slf4j
@Service
public class HandleWalletRefundResultService implements HandleWalletRefundResultUseCase {

    // 지갑이 알려 준 금액·주문·구매자가 환불과 다를 때 지갑 줄의 이유에 남기는 값. 지갑의 reason과 겹치지 않게 대문자 상수로 둔다
    static final String RESULT_MISMATCH = "RESULT_MISMATCH";

    // 로그 문구(label)와 분기를 분리하려고 둔 종류. 문구를 고쳐도 분기가 바뀌지 않게 한다
    private enum ResultKind {
        COMPLETED("완료"),
        FAILED("실패");

        private final String label;

        ResultKind(String label) {
            this.label = label;
        }
    }

    // 잠근 구간에서 표시를 남겼는지와 못 남긴 이유. 이유마다 로그 수준이 달라(바뀜은 INFO, 지갑 완료 뒤 모순은 WARN) 참·거짓으로는 부족하다
    private enum MarkOutcome {
        MARKED,
        CHANGED,
        AFTER_WALLET_SUCCESS
    }

    // 먼저 읽은 값. 거르는 판단에만 쓰고, 기록·완료할 때는 잠근 뒤 다시 읽는다.
    // walletStep은 없을 수 있다(#574 전에 승인된 환불은 단계 줄이 없다). productStep은 지갑 완료를 반영하기 전이면 없다
    private record Snapshot(Refund refund, RefundStep productStep, RefundStep walletStep) {
    }

    private final RefundPersistencePort refundPersistencePort;
    private final RefundStepPersistencePort refundStepPersistencePort;
    private final ProductStockReleasePort productStockReleasePort;
    private final RefundStepRunner refundStepRunner;
    private final Clock clock;
    // 거르기 위한 읽기용. 잠그지 않는다
    private final TransactionTemplate readOnlyTransaction;
    // 자기 행을 잠그고 바꾸는 구간
    private final TransactionTemplate transaction;

    public HandleWalletRefundResultService(RefundPersistencePort refundPersistencePort,
                                           RefundStepPersistencePort refundStepPersistencePort,
                                           ProductStockReleasePort productStockReleasePort,
                                           RefundStepRunner refundStepRunner, Clock clock,
                                           PlatformTransactionManager transactionManager) {
        this.refundPersistencePort = refundPersistencePort;
        this.refundStepPersistencePort = refundStepPersistencePort;
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
        Snapshot snapshot = readSnapshot(refundId);
        Refund refund = snapshot.refund();
        if (!isProcessingOrLog(refund, refundId, ResultKind.COMPLETED)) {
            return;
        }
        // 지갑이 돌려준 금액·주문·구매자가 환불이 요청한 것과 다르면 완료하지 않는다. 돈이 어긋난 채 끝내면 되돌릴 방법이 없어 사람이 보게 한다
        // (지갑에 그 반환을 취소해 달라고 보내는 것은 별도 이슈, #544 결정 13)
        if (!matchesRefund(refund, command.orderId(), command.buyerId(), command.amount())) {
            recordMismatch(refundId, command.orderId(), command.buyerId(), command.amount(), null, snapshot);
            return;
        }
        if (!startProductStep(refundId)) {
            log.info("재고 복구를 시작하려는 사이 환불이 바뀌어 넘김. refundId={}", refundId);
            return;
        }
        // 재고 복구는 구간 밖. 상품은 같은 refundId면 또 풀지 않아 같은 메시지가 다시 와도 안전하다.
        // 실패하면 틀이 재고 실패를 기록(재고 줄은 다시 시도 대기)하고 예외를 다시 던진다. 오류 처리기가 다시 시도한다
        refundStepRunner.run(refundId, RefundStepType.PRODUCT, () -> {
            productStockReleasePort.releaseStock(new StockRelease(
                    refundId, refund.getOrderId(), refund.getProductOptionId(), refund.getQuantity()));
            return null;
        });
        complete(refundId);
    }

    @Override
    public void recordWalletRefundFailure(WalletRefundFailedCommand command) {
        Long refundId = command.refundId();
        Snapshot snapshot = readSnapshot(refundId);
        Refund refund = snapshot.refund();
        if (!isProcessingOrLog(refund, refundId, ResultKind.FAILED)) {
            return;
        }
        // 금액·주문·구매자 비교보다 먼저 본다. 달라서 불일치로 기록하는 경우에도 재고 단계가 시작된 환불에 지갑 실패를 남기면 안 되기 때문이다
        if (isAfterWalletSuccess(refund, snapshot.productStep())) {
            logIgnoredAfterWalletSuccess(refund, snapshot.productStep(), "실패");
            return;
        }
        // 금액·주문·구매자가 다르면 메시지의 어느 값이 틀렸는지 알 수 없다. 지갑 결과가 어긋난 것 자체가 문제라
        // 완료 경로와 같이 불일치로 기록해 사람이 보게 한다(엉뚱한 환불에 표시가 붙을 수 있음은 감수, #544 결정 7·12).
        // 실패 메시지의 금액은 환불이 요청한 금액을 그대로 돌려준다고 본다(지갑 제안에 뜻이 없어 둔 가정, 결정 12).
        // 지갑이 알려 준 이유는 기록 대신 로그에 남는다. 예외를 던지면 오류 처리기가 같은 메시지를 다시 시도해 줄이 막히므로 던지지 않는다
        if (!matchesRefund(refund, command.orderId(), command.buyerId(), command.amount())) {
            recordMismatch(refundId, command.orderId(), command.buyerId(), command.amount(), command.reason(), snapshot);
            return;
        }
        if (isSameFailure(refund, snapshot.walletStep(), command.reason())) {
            log.info("같은 지갑 반환 실패가 이미 기록돼 넘김. refundId={}, reason={}", refundId, command.reason());
            return;
        }
        MarkOutcome outcome = transaction.execute(status -> {
            // 읽은 뒤 잠그기 전에 상태나 기록이 바뀔 수 있어 잠근 뒤 다시 본다. 이 행을 잠그는 첫 조회라 옛 값을 쓰지 않는다
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(refundId);
            if (locked.isEmpty()) {
                return MarkOutcome.CHANGED;
            }
            // 단계 줄은 환불 행을 잠근 뒤에 읽는다(결정 4, 잠금 순서 환불 행 → 단계 줄)
            Optional<RefundStep> walletStep = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET);
            // 처리 중 확인보다 먼저 본다. 그사이 완료됐으면 "바뀜"(INFO)이 아니라 읽을 때 완료였던 경우와 같은 모순(WARN)이다
            RefundStep productStep = findProductStep(refundId);
            if (isAfterWalletSuccess(locked.get(), productStep)) {
                logIgnoredAfterWalletSuccess(locked.get(), productStep, "실패");
                return MarkOutcome.AFTER_WALLET_SUCCESS;
            }
            if (locked.get().getStatus() != RefundStatus.PROCESSING
                    || isSameFailure(locked.get(), walletStep.orElse(null), command.reason())) {
                return MarkOutcome.CHANGED;
            }
            // 환불 행은 바꾸지 않는다(실패는 지갑 줄에만 남는다, 결정 3). 환불 행은 단계 줄을 지키려고 잠근 것이다
            stopWalletStep(refundId, walletStep, command.reason());
            return MarkOutcome.MARKED;
        });
        if (outcome == MarkOutcome.AFTER_WALLET_SUCCESS) {
            return;
        }
        if (outcome != MarkOutcome.MARKED) {
            log.info("지갑 반환 실패를 기록하려는 사이 환불이 바뀌어 넘김. refundId={}", refundId);
            return;
        }
        // 구간이 커밋된 뒤에 남긴다. 구간이 실패했는데 "기록함"이 남으면 실제와 어긋난다
        log.warn("지갑 반환 실패를 환불에 기록함(처리 중 유지). refundId={}, orderId={}, amount={}, reason={}",
                refundId, command.orderId(), command.amount(), command.reason());
    }

    // 없는 환불이나 처리 중이 아닌 환불이면 이유를 남기고 false.
    // 이미 완료된 환불의 완료 메시지는 같은 메시지가 두 번 온 정상(지갑은 첫 결과만 다시 보낸다)이라 INFO.
    // 완료된 환불에 실패 메시지가 오면 중복이 아니라 앞선 완료 결과와 모순이므로 WARN으로 사람이 확인하게 한다(상태는 바꾸지 않는다).
    // 그 밖(요청됨·거절·철회, 없는 환불)도 지갑이 돈을 움직인 환불이 이 상태일 수 없어 WARN.
    // WARN인 이유: 예외 없이 넘기지만 사람이 확인해야 하는 이상이다(service.md 로그 표, ERROR는 처리되지 못한 예외)
    private boolean isProcessingOrLog(Refund refund, Long refundId, ResultKind kind) {
        if (refund == null) {
            log.warn("지갑 반환 {} 메시지의 환불이 없어 넘김. refundId={}", kind.label, refundId);
            return false;
        }
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            if (kind == ResultKind.FAILED) {
                log.warn("이미 완료된 환불에 지갑 반환 실패 메시지가 와 넘김(앞선 완료 결과와 모순, 확인 필요). refundId={}", refundId);
            } else {
                log.info("이미 완료된 환불의 지갑 반환 {} 메시지라 넘김. refundId={}", kind.label, refundId);
            }
            return false;
        }
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            log.warn("처리 중이 아닌 환불의 지갑 반환 {} 메시지라 넘김. refundId={}, status={}", kind.label, refundId, refund.getStatus());
            return false;
        }
        return true;
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

    // 지갑 완료가 이미 반영된 환불이다. 완료됐거나, 지갑 완료를 받아 재고 단계가 시작된(재고 줄이 있는) 경우다.
    // 재고 줄은 지갑 완료를 반영하는 구간에서만 만든다. 어디까지 왔는지는 #545가 어디부터 다시 할지, #546이 무엇을 되돌릴지 정하는 기준이라,
    // 지갑 결과로 지갑 실패를 남기면 돈을 다시 가져오지 않고 주문만 되돌려 구매자가 돈과 상품을 모두 받을 수 있다. 그래서 지갑 결과로는 남기지 않는다
    private static boolean isAfterWalletSuccess(Refund refund, RefundStep productStep) {
        return refund.getStatus() == RefundStatus.COMPLETED || productStep != null;
    }

    // 앞선 지갑 완료와 모순된 결과라 사람이 확인해야 해 WARN으로 남긴다(읽을 때 완료였던 환불에 실패 메시지가 온 경우와 같은 취급).
    // 잠근 구간 안에서 불러도 된다. 아무것도 쓰지 않고 넘기는 경우라 커밋 전후로 결과가 달라지지 않는다.
    // 재고 줄 상태를 함께 남겨 재고 단계가 어디까지 왔는지(시도 중·다시 시도 대기·성공·멈춤) 로그만으로 알 수 있게 한다
    private static void logIgnoredAfterWalletSuccess(Refund refund, RefundStep productStep, String what) {
        String productStepStatus = productStep == null ? "없음" : productStep.getStatus().name();
        log.warn("지갑 완료가 이미 반영된 환불(완료 또는 재고 단계가 시작됨)이라 지갑 반환 {}를 기록하지 않고 넘김(앞선 지갑 완료와 모순, 확인 필요). refundId={}, status={}, productStepStatus={}",
                what, refund.getId(), refund.getStatus(), productStepStatus);
    }

    // walletStep은 없을 수 있다(#574 전에 승인된 환불). 없으면 남은 지갑 실패가 없는 것이다
    private static boolean isSameFailure(Refund refund, RefundStep walletStep, String reason) {
        return refund.getStatus() == RefundStatus.PROCESSING
                && walletStep != null
                && walletStep.isStoppedWith(reason);
    }

    // 지갑 줄을 멈춤으로 남긴다. 환불 행을 잠근 구간 안에서만 부른다.
    // 줄이 없으면(#574 전에 승인된 환불) 시도 중으로 만든 뒤 바로 멈춰 저장한다. 실패를 남길 곳이 있어야 사람이 찾기 때문이다
    private void stopWalletStep(Long refundId, Optional<RefundStep> walletStep, String reason) {
        if (walletStep.isPresent()) {
            if (walletStep.get().stop(reason)) {
                refundStepPersistencePort.update(walletStep.get());
            }
            return;
        }
        RefundStep created = RefundStep.start(refundId, RefundStepType.WALLET, LocalDateTime.now(clock));
        created.stop(reason);
        refundStepPersistencePort.saveNew(created);
    }

    // 지갑 결과의 주문·구매자·금액이 모두 환불과 같은지 본다. 완료·실패 메시지가 같은 기준을 쓰게 한 곳에 둔다.
    // ID는 equals로 비교한다(Long을 ==로 비교하면 캐시 범위 밖 값에서 같아도 다르다고 나온다)
    private static boolean matchesRefund(Refund refund, Long orderId, Long buyerId, long amount) {
        return refund.getOrderId().equals(orderId)
                && refund.getBuyerId().equals(buyerId)
                && refund.getRefundAmount() == amount;
    }

    // 금액·주문·구매자가 다른 완료·실패 메시지를 불일치로 기록한다. 완료도 재고 복구도 하지 않는다.
    // 두 메시지가 같은 규칙을 따르게 한 곳에서 기록한다(한쪽만 고쳐 동작이 갈라지지 않게).
    // walletReason은 실패 메시지가 알려 준 이유다(완료 메시지면 null). 기록은 불일치로 덮이므로 로그로 남긴다
    private void recordMismatch(Long refundId, Long messageOrderId, Long messageBuyerId, long messageAmount,
                                String walletReason, Snapshot snapshot) {
        Refund refund = snapshot.refund();
        if (isAfterWalletSuccess(refund, snapshot.productStep())) {
            logIgnoredAfterWalletSuccess(refund, snapshot.productStep(), "결과 불일치");
            return;
        }
        MarkOutcome outcome = transaction.execute(status -> {
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(refundId);
            if (locked.isEmpty()) {
                return MarkOutcome.CHANGED;
            }
            RefundStep productStep = findProductStep(refundId);
            if (isAfterWalletSuccess(locked.get(), productStep)) {
                logIgnoredAfterWalletSuccess(locked.get(), productStep, "결과 불일치");
                return MarkOutcome.AFTER_WALLET_SUCCESS;
            }
            if (locked.get().getStatus() != RefundStatus.PROCESSING) {
                return MarkOutcome.CHANGED;
            }
            // 환불 행은 바꾸지 않는다(불일치는 지갑 줄에만 남는다, 결정 3). 환불 행은 단계 줄을 지키려고 잠근 것이다
            stopWalletStep(refundId,
                    refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET), RESULT_MISMATCH);
            return MarkOutcome.MARKED;
        });
        if (outcome == MarkOutcome.AFTER_WALLET_SUCCESS) {
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

    // 지갑 완료를 표에 반영하고 재고 줄을 "시도 중"으로 둔 뒤 커밋한다. 재고를 부르기 전에 커밋해 서버가 그 사이 죽어도 "어디까지 왔나"가 남는다(결정 5).
    // 지갑 줄: 없으면(#574 전에 승인된 환불) 만들어 성공으로 저장한다. 앞서 실패·불일치로 멈춰 있으면 멈춤 그대로 둔다
    // (끝난 줄은 바뀌지 않는다. 지갑이 실패를 알렸던 기록을 남기려는 것, #544 결정 8). 환불은 그래도 완료로 간다.
    // 재고 줄: 없으면 만들고, 다시 시도 대기(앞선 재고 실패)면 다시 시작한다. 이미 시도 중이면 같은 메시지가 동시에 처리 중이거나
    // 앞 시도가 멈춘 것이라 그대로 두고 진행한다(상품은 같은 refundId면 또 풀지 않는다).
    // 잠근 뒤 처리 중이 아니면(그사이 같은 메시지가 먼저 완료 등) 아무것도 쓰지 않고 false
    private boolean startProductStep(Long refundId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Optional<Refund> locked = refundPersistencePort.findByIdForUpdate(refundId);
            if (locked.isEmpty() || locked.get().getStatus() != RefundStatus.PROCESSING) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            Optional<RefundStep> walletStep = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET);
            if (walletStep.isPresent()) {
                if (walletStep.get().succeed()) {
                    refundStepPersistencePort.update(walletStep.get());
                }
            } else {
                RefundStep created = RefundStep.start(refundId, RefundStepType.WALLET, now);
                created.succeed();
                refundStepPersistencePort.saveNew(created);
            }
            Optional<RefundStep> productStep = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.PRODUCT);
            if (productStep.isEmpty()) {
                refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.PRODUCT, now));
            } else if (productStep.get().begin(now)) {
                refundStepPersistencePort.update(productStep.get());
            }
            return true;
        }));
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
            // 재고 줄 성공과 환불 완료를 같은 구간에서 함께 저장한다(결정 5). 따로 저장하면 하나만 남아 표와 환불이 어긋날 수 있다
            refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.PRODUCT).ifPresent(productStep -> {
                if (productStep.succeed()) {
                    refundStepPersistencePort.update(productStep);
                }
            });
            return true;
        }));
        if (!completed) {
            log.info("재고 복구를 마친 사이 환불이 이미 바뀌어 완료하지 않고 넘김. refundId={}", refundId);
        }
    }
}
