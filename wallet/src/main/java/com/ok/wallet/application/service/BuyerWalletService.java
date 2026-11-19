package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.BuyerBalanceResult;
import com.ok.wallet.application.dto.OrderPaymentResult;
import com.ok.wallet.application.dto.OrderRefundResult;
import com.ok.wallet.application.dto.OrderRefundRevertResult;
import com.ok.wallet.application.port.in.BuyerWalletUseCase;
import com.ok.wallet.application.port.out.BuyerWalletLedgerRepository;
import com.ok.wallet.application.port.out.BuyerWalletRepository;
import com.ok.wallet.application.port.out.WalletOrderPaymentRepository;
import com.ok.wallet.application.port.out.WalletOrderRefundRepository;
import com.ok.wallet.application.port.out.WalletOrderRefundRevertRepository;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.domain.WalletOrderPayment;
import com.ok.wallet.domain.WalletOrderPaymentStatus;
import com.ok.wallet.domain.WalletOrderRefund;
import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import com.ok.wallet.domain.WalletOrderRefundRevert;
import com.ok.wallet.domain.WalletOrderRefundRevertFailureReason;
import com.ok.wallet.domain.WalletOrderRefundRevertStatus;
import com.ok.wallet.domain.WalletOrderRefundStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerWalletService implements BuyerWalletUseCase {
    private final BuyerWalletRepository buyerWalletRepository;
    private final BuyerWalletLedgerRepository buyerWalletLedgerRepository;
    private final WalletOrderPaymentRepository walletOrderPaymentRepository;
    private final WalletOrderRefundRepository walletOrderRefundRepository;
    private final WalletOrderRefundRevertRepository walletOrderRefundRevertRepository;
    // 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
    // 지갑 생성 시각을 LocalDateTime.now()로 직접 만들면 실행 환경의 시간대를 따라가고, 테스트에서 값을 고정할 수 없다 (#93)
    private final Clock clock;

    /**
     * 구매자 지갑을 잠금 조회로 반환한다. 지갑이 없다면 생성한 뒤 반환한다.
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     * 반환된 지갑은 호출한 트랜잭션이 끝날 때까지 잠겨 있으므로 잔액 조회처럼 읽기만 하는 곳에는 쓰지 않는다.
     * 엔티티를 돌려주므로 입력 포트에 두지 않고, 충전 반영처럼 잔액을 바꾸는 application 안쪽에서만 쓴다.
     */
    @Transactional
    public BuyerWallet getOrCreate(Long memberId) {
        // 그대로 두면 DB의 NOT NULL 제약 오류로 터져, 호출하는 쪽의 잘못인지 DB 문제인지 바로 알기 어렵다
        if (memberId == null) {
            throw new IllegalArgumentException("회원 ID는 필수입니다.");
        }
        if (!buyerWalletRepository.existsByMemberId(memberId)) {
            buyerWalletRepository.insertIfAbsent(memberId, LocalDateTime.now(clock));
        }

        // 바로 앞에서 저장했거나 존재를 확인했으므로 여기서 없을 수 없다.
        // 없다면 사용자 입력이 아닌 코드·DB의 이상이므로 500으로 드러나도록 IllegalStateException을 던진다.
        return buyerWalletRepository.findForUpdateByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException("구매자 지갑 생성 후 조회에 실패했습니다. memberId=" + memberId));
    }

    /**
     * 토스 결제 승인으로 예치금을 충전한다. 지갑을 잠근 뒤 같은 결제가 이미 반영됐는지 확인하고 잔액을 늘린다.
     * 근거 키(CHARGE:{paymentKey})는 여기서 만들어, 호출하는 쪽이 접두어 규칙을 몰라도 되게 한다.
     */
    @Transactional
    public void chargeByPayment(Long memberId, String paymentKey, long amount) {
        String referenceKey = "CHARGE:" + paymentKey;
        BuyerWallet wallet = getOrCreate(memberId);
        // 이 지갑에 이미 반영한 근거 키면 넘어간다. 지갑을 잠근 뒤 확인하므로 같은 지갑의 요청은 순서대로 이 확인을 거친다.
        // 다른 지갑에 같은 키가 있는 경우는 일부러 넘기지 않는다. 조용히 무시하지 않고 저장 시 유니크 제약으로 실패하게 해 오류를 드러낸다.
        // 같은 지갑에서 같은 키가 동시에 확인을 통과하더라도 유니크 제약이 두 번째 저장을 막는다.
        if (buyerWalletLedgerRepository.existsByBuyerWalletIdAndReferenceKey(wallet.getId(), referenceKey)) {
            log.info("이미 반영된 근거 키라 충전을 건너뜁니다. buyerWalletId={}, referenceKey={}", wallet.getId(), referenceKey);
            return;
        }
        BuyerWalletLedger ledger = wallet.charge(amount, referenceKey);
        buyerWalletLedgerRepository.save(ledger);
    }

    @Override
    @Transactional(readOnly = true)
    public long getBalance(Long memberId) {
        return buyerWalletRepository.findByMemberId(memberId)
                .map(BuyerWallet::getBalance)
                .orElse(0L);
    }

    @Override
    @Transactional(readOnly = true)
    public BuyerBalanceResult getBalanceSummary(Long memberId) {
        return new BuyerBalanceResult(getBalance(memberId), BuyerWallet.MAX_BALANCE);
    }

    /**
     * 주문 결제 금액을 구매자 지갑에서 차감하고, 지갑의 결제 처리 결과를 기록한다.
     * 같은 주문이 다시 오면 처음 결과(성공·실패)를 그대로 돌려주고 아무것도 바꾸지 않는다.
     * 실패도 기록해, 잔액 부족으로 실패한 주문이 재전달 후 충전된 잔액으로 결제되지 않게 한다.
     * 지갑을 잠근 뒤 확인하므로, 같은 회원의 결제·충전이 동시에 와도 순서대로 처리된다.
     * 지갑이 없으면 잔액 0원인 지갑을 만든 뒤 판단하므로 잔액 부족이 된다.
     *
     * <p>회원 ID·주문 ID는 null이 아니고 금액은 0보다 커야 한다. 호출하는 쪽이 걸러 넘기며, 아니면 IllegalArgumentException을 던진다.
     *
     * <p>같은 주문인지는 처리 기록을 먼저 넣어 보고 판단한다(#360). 일반 조회로 판단하면, 이 메서드가 이미 열린 트랜잭션(주문 생성 리스너)에
     * 합류할 때 잠금 전에 잡힌 스냅샷 때문에 동시에 먼저 끝난 결제를 못 보고 다시 차감하려다 원장 유니크 제약에 걸려 예외로 끝났다.
     * 넣기는 스냅샷이 아니라 유니크 제약으로 판단하므로, 호출하는 쪽의 트랜잭션·격리 수준과 상관없이 중복을 막는다.
     *
     * @param orderSummary 이용 내역에 보일 주문 요약. 처리 기록에 함께 남기고, 비었거나 길어도 결제는 한다
     */
    @Transactional
    public OrderPaymentResult payForOrder(Long memberId, Long orderId, long amount, String orderSummary) {
        // 호출하는 쪽(OrderPaymentService)이 값을 걸러 넘긴다. 처리 기록 넣기(INSERT IGNORE)는 NULL·CHECK 위반도 오류 없이 넘기므로
        // 잘못된 값이 기록되지 않게 여기서 먼저 표준 예외로 막는다
        if (memberId == null || orderId == null) {
            throw new IllegalArgumentException("회원 ID와 주문 ID는 필수입니다. memberId=" + memberId + ", orderId=" + orderId);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다. amount=" + amount);
        }
        BuyerWallet wallet = getOrCreate(memberId);
        boolean enoughBalance = wallet.hasEnoughBalance(amount);
        WalletOrderPaymentStatus status = enoughBalance ? WalletOrderPaymentStatus.PAID : WalletOrderPaymentStatus.FAILED;
        // DB는 마이크로초까지 저장한다. 자르지 않으면 처음 결과와 기록에서 다시 읽은 결과의 시각이 달라진다
        LocalDateTime processedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        int inserted = walletOrderPaymentRepository.insertIfAbsent(orderId, memberId, amount,
                WalletOrderPayment.normalizeOrderSummary(orderSummary), status.name(), processedAt);
        if (inserted == 0) {
            // 이미 처리된 주문이다. 기록은 넣기를 거쳐 있음이 확인됐으므로 없을 수 없다. 없다면 코드·DB의 이상이라 500으로 드러낸다
            // IGNORE는 중복이 아닌 오류(CHECK 위반 등)로 넣기를 건너뛰어도 0을 돌려주므로, 원인을 좁힐 수 있게 그 가능성을 메시지에 남긴다
            WalletOrderPayment processed = walletOrderPaymentRepository.findForShareByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException(
                            "주문 결제 처리 기록 넣기 결과가 0건인데 기록이 없습니다. 중복이 아닌 이유로 넣기가 무시됐을 수 있습니다. orderId="
                                    + orderId + ", memberId=" + memberId + ", amount=" + amount));
            return OrderPaymentResult.alreadyProcessed(processed);
        }

        OrderPaymentResult result = OrderPaymentResult.processed(orderId, memberId, amount, status, processedAt);
        if (!enoughBalance) {
            return result;
        }
        // 원장 근거 키는 마지막 안전장치로 남긴다 (같은 주문이 두 번 기록되려 하면 유니크 제약이 막는다)
        // 키 규칙은 이용 내역 조회와 같은 곳(PaymentReferenceKey)에서 만든다
        buyerWalletLedgerRepository.save(wallet.pay(amount, PaymentReferenceKey.of(orderId)));
        return result;
    }

    /**
     * 주문의 결제 만료 요청을 처리 기록에 남긴다. 처음 보는 주문이면 만료로 기록하고 돈은 움직이지 않는다.
     * 이미 처리한 주문이면 아무것도 바꾸지 않고 처음 기록(결제됨·잔액 부족·만료)을 돌려준다.
     * 만료로 기록된 주문에 결제 요청이 늦게 와도 처리 기록 넣기가 걸러 차감하지 않는다.
     *
     * <p>결제({@link #payForOrder})와 같은 순서로 구매자 지갑을 먼저 잠근 뒤 기록을 넣는다. 결제 요청과 만료 요청이 동시에 와도
     * 먼저 잠근 쪽의 결과 하나만 남고, 잠그는 순서가 같아 서로 막혀 멈추지 않는다.
     * 회원 ID·주문 ID는 null이 아니고 금액은 0보다 커야 한다. 아니면 IllegalArgumentException을 던진다.
     */
    @Transactional
    public OrderPaymentResult expireOrderPayment(Long memberId, Long orderId, long amount) {
        // 처리 기록 넣기(INSERT IGNORE)는 NULL·CHECK 위반도 오류 없이 넘기므로 잘못된 값이 기록되지 않게 먼저 막는다
        if (memberId == null || orderId == null) {
            throw new IllegalArgumentException("회원 ID와 주문 ID는 필수입니다. memberId=" + memberId + ", orderId=" + orderId);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다. amount=" + amount);
        }
        getOrCreate(memberId);
        LocalDateTime processedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        int inserted = walletOrderPaymentRepository.insertIfAbsent(
                orderId, memberId, amount, null, WalletOrderPaymentStatus.EXPIRED.name(), processedAt);
        if (inserted == 0) {
            WalletOrderPayment processed = walletOrderPaymentRepository.findForShareByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException(
                            "주문 결제 처리 기록 넣기 결과가 0건인데 기록이 없습니다. 중복이 아닌 이유로 넣기가 무시됐을 수 있습니다. orderId="
                                    + orderId + ", memberId=" + memberId + ", amount=" + amount));
            return OrderPaymentResult.alreadyProcessed(processed);
        }
        return OrderPaymentResult.processed(orderId, memberId, amount, WalletOrderPaymentStatus.EXPIRED, processedAt);
    }

    /**
     * 이미 처리한 주문 결제의 처음 결과를 찾는다. 처리 기록은 한 번 남으면 바뀌지 않는다.
     */
    @Transactional(readOnly = true)
    public Optional<OrderPaymentResult> findProcessedOrderPayment(Long orderId) {
        return walletOrderPaymentRepository.findByOrderId(orderId).map(OrderPaymentResult::alreadyProcessed);
    }

    /**
     * 환불이 승인한 금액을 구매자 지갑에 돌려주고, 지갑의 반환 처리 결과를 기록한다.
     * 같은 환불이 다시 오면 처음 결과(돌려줌·거절)를 그대로 돌려주고 아무것도 바꾸지 않는다. 금액이 달라도 처음 기록이 기준이다.
     * 그 주문·구매자의 성공한 결제가 없거나, 같은 주문의 반환 합계가 결제 금액을 넘으면 돌려주지 않고 거절로 기록한다.
     * 돌려줄 돈이라 보유 한도는 보지 않는다.
     *
     * <p>환불 ID·주문 ID·회원 ID는 null이 아니고 금액은 0보다 커야 한다. 호출하는 쪽이 걸러 넘기며, 아니면 IllegalArgumentException을 던진다.
     *
     * <p>같은 주문의 환불이 동시에 와도 합계를 넘지 않게, 지갑을 잠근 뒤 반환 합계를 잠금 조회(최신 커밋 기준)로 읽는다.
     * 일반 조회는 지갑 잠금을 기다리기 전에 잡힌 스냅샷을 봐서 방금 끝난 반환을 빼고 더한다.
     * READ COMMITTED는 정확성이 아니라 교착을 피하려는 것이다. 기본 격리 수준에서는 반환 기록이 없는 주문을 잠금 조회하면
     * 빈 구간까지 잠겨, 이웃한 주문의 첫 반환 둘이 서로의 기록 넣기를 막아 교착이 난다. 이미 열린 트랜잭션에 합류해 격리 수준이
     * 무시되어도 합계는 여전히 맞다(교착이 나면 Kafka가 다시 시도한다).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderRefundResult refundForOrder(Long refundId, Long orderId, Long memberId, long amount) {
        // 처리 기록 넣기(INSERT IGNORE)는 NULL·CHECK 위반도 오류 없이 넘기므로 잘못된 값이 기록되지 않게 먼저 막는다
        if (refundId == null || orderId == null || memberId == null) {
            throw new IllegalArgumentException("환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=" + refundId
                    + ", orderId=" + orderId + ", memberId=" + memberId);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("반환 금액은 0보다 커야 합니다. amount=" + amount);
        }
        // 결제 처리 기록은 한 번 남으면 바뀌지 않아, 거절인지는 지갑을 잠그지 않고 판단해도 같다
        // 구매자가 다르면 결제가 없는 것과 같이 본다. 다른 사람의 지갑에 돈이 들어가지 않게 한다
        Optional<WalletOrderPayment> payment = walletOrderPaymentRepository.findByOrderId(orderId)
                .filter(WalletOrderPayment::isPaid)
                .filter(paid -> paid.getBuyerId().equals(memberId));
        if (payment.isEmpty()) {
            return recordRefund(refundId, orderId, memberId, amount, WalletOrderRefundStatus.FAILED,
                    WalletOrderRefundFailureReason.PAYMENT_NOT_FOUND);
        }

        // 지갑을 먼저 잠가 같은 구매자의 반환·충전·결제를 순서대로 처리한다. 한 주문은 구매자가 하나라 같은 주문의 반환도 순서대로 온다
        BuyerWallet wallet = getOrCreate(memberId);
        long refunded = walletOrderRefundRepository.sumCompletedAmountForShareByOrderId(orderId);
        // 합계에 더하면 아주 큰 금액에서 long 범위를 넘을 수 있어 남은 금액과 비교한다
        if (amount > payment.get().getAmount() - refunded) {
            return recordRefund(refundId, orderId, memberId, amount, WalletOrderRefundStatus.FAILED,
                    WalletOrderRefundFailureReason.REFUND_TOTAL_EXCEEDED);
        }
        OrderRefundResult result = recordRefund(refundId, orderId, memberId, amount, WalletOrderRefundStatus.COMPLETED, null);
        if (!result.alreadyProcessed()) {
            // 원장 근거 키는 마지막 안전장치로 남긴다 (같은 환불이 두 번 기록되려 하면 유니크 제약이 막는다)
            buyerWalletLedgerRepository.save(wallet.refund(amount, RefundReferenceKey.of(refundId)));
        }
        return result;
    }

    // 처리 기록을 넣어 보고, 이미 있으면 처음 기록을 돌려준다(#360과 같은 방식)
    private OrderRefundResult recordRefund(Long refundId, Long orderId, Long memberId, long amount,
                                           WalletOrderRefundStatus status, WalletOrderRefundFailureReason failureReason) {
        int inserted = walletOrderRefundRepository.insertIfAbsent(refundId, orderId, memberId, amount, status.name(),
                failureReason == null ? null : failureReason.name(), LocalDateTime.now(clock));
        if (inserted == 0) {
            // 넣기를 거쳐 있음이 확인됐으므로 없을 수 없다. 없다면 중복이 아닌 이유(CHECK 위반 등)로 넣기가 무시된 것이라 500으로 드러낸다
            WalletOrderRefund processed = walletOrderRefundRepository.findForShareByRefundId(refundId)
                    .orElseThrow(() -> new IllegalStateException(
                            "환불 반환 처리 기록 넣기 결과가 0건인데 기록이 없습니다. 중복이 아닌 이유로 넣기가 무시됐을 수 있습니다. refundId="
                                    + refundId + ", orderId=" + orderId + ", memberId=" + memberId + ", amount=" + amount));
            // 되돌리기 기록은 바뀌지 않아 잠그지 않고 본다. 되돌리기와 동시에 오면 처음 결과(돌려줌)가 나갈 수 있는데,
            // 환불은 이미 끝난 환불의 반환 결과를 기록만 하고 넘기므로 어느 쪽이 나가도 된다
            boolean revertedAfterRefund = processed.isCompleted()
                    && walletOrderRefundRevertRepository.findByRefundId(refundId)
                    .filter(WalletOrderRefundRevert::isReverted)
                    .isPresent();
            return revertedAfterRefund
                    ? OrderRefundResult.revertedAfterRefund(processed)
                    : OrderRefundResult.alreadyProcessed(processed);
        }
        return OrderRefundResult.processed(refundId, orderId, memberId, amount, status, failureReason);
    }

    /**
     * 환불 반환으로 돌려준 예치금을 다시 가져오고, 지갑의 되돌리기 처리 결과를 기록한다.
     * 요청의 구매자·금액이 아니라 반환 처리 기록의 구매자·금액으로 가져온다. 다른 구매자에게 잘못 돌려준 반환도 돈을 받은 지갑에서 가져와야 한다.
     * 잔액이 모자라면 마이너스로 두지 않고 가져오지 않은 채 실패로 기록한다.
     * 같은 되돌리기가 다시 오면 처음 결과를 그대로 돌려주고 아무것도 바꾸지 않는다.
     *
     * <p>돌려준 적 없는 환불은 원장을 바꾸지 않고 다시 가져옴으로 기록한다. 반환 기록이 아예 없으면 반환 거절({@code REFUND_REVERTED})을
     * 먼저 넣는다. 되돌리기와 반환 요청은 토픽이 달라 순서가 바뀌어 올 수 있는데, 반환 기록이 환불 하나에 한 건이라
     * 뒤에 온 같은 환불의 반환 요청은 이 거절을 처음 결과로 받고 돈을 주지 않는다.
     *
     * <p>환불·주문·회원 ID는 null이 아니고 금액은 0보다 커야 한다. 호출하는 쪽이 걸러 넘기며, 아니면 IllegalArgumentException을 던진다.
     *
     * <p>잠금 순서는 반환 처리와 같이 지갑 → 기록이다. 반환 기록은 바뀌지 않아 잠그지 않고 읽어 가져올 지갑을 먼저 정한다.
     * READ COMMITTED를 쓰는 이유는 반환 처리와 같다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderRefundRevertResult revertRefundForOrder(Long refundId, Long orderId, Long memberId, long amount) {
        // 반환 거절을 넣을 때 INSERT IGNORE가 NULL·CHECK 위반도 오류 없이 넘기므로 잘못된 값이 기록되지 않게 먼저 막는다
        if (refundId == null || orderId == null || memberId == null) {
            throw new IllegalArgumentException("환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=" + refundId
                    + ", orderId=" + orderId + ", memberId=" + memberId);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("되돌릴 금액은 0보다 커야 합니다. amount=" + amount);
        }
        WalletOrderRefund refund = walletOrderRefundRepository.findByRefundId(refundId)
                .orElseGet(() -> blockRefund(refundId, orderId, memberId, amount));
        if (!refund.isCompleted()) {
            return recordRevert(refund, WalletOrderRefundRevertStatus.REVERTED, null);
        }

        // 지갑을 먼저 잠가 같은 구매자의 되돌리기·반환·충전·결제를 순서대로 처리하고, 잠근 뒤의 잔액으로 가져올 수 있는지 정한다
        BuyerWallet wallet = getOrCreate(refund.getBuyerId());
        OrderRefundRevertResult result = wallet.hasEnoughBalance(refund.getAmount())
                ? recordRevert(refund, WalletOrderRefundRevertStatus.REVERTED, null)
                : recordRevert(refund, WalletOrderRefundRevertStatus.FAILED,
                        WalletOrderRefundRevertFailureReason.INSUFFICIENT_BALANCE);
        if (!result.alreadyProcessed() && result.reverted()) {
            // 원장 근거 키는 마지막 안전장치로 남긴다 (같은 되돌리기가 두 번 기록되려 하면 유니크 제약이 막는다)
            buyerWalletLedgerRepository.save(
                    wallet.revertRefund(refund.getAmount(), RefundRevertReferenceKey.of(refundId)));
        }
        return result;
    }

    // 반환 기록이 없으면 거절을 넣어 뒤에 올 반환 요청을 막는다. 그사이 반환 요청이 먼저 기록했으면 그 기록을 따른다
    private WalletOrderRefund blockRefund(Long refundId, Long orderId, Long memberId, long amount) {
        walletOrderRefundRepository.insertIfAbsent(refundId, orderId, memberId, amount,
                WalletOrderRefundStatus.FAILED.name(), WalletOrderRefundFailureReason.REFUND_REVERTED.name(),
                LocalDateTime.now(clock));
        // 넣었든 다른 요청이 먼저 넣었든 기록은 있다. 없다면 중복이 아닌 이유로 넣기가 무시된 것이라 500으로 드러낸다
        return walletOrderRefundRepository.findForShareByRefundId(refundId)
                .orElseThrow(() -> new IllegalStateException(
                        "환불 되돌리기 중 반환 거절을 넣었는데 반환 기록이 없습니다. refundId=" + refundId
                                + ", orderId=" + orderId + ", memberId=" + memberId + ", amount=" + amount));
    }

    // 되돌리기 기록을 넣어 보고, 이미 있으면 처음 기록을 돌려준다(반환 처리와 같은 방식)
    private OrderRefundRevertResult recordRevert(WalletOrderRefund refund, WalletOrderRefundRevertStatus status,
                                                 WalletOrderRefundRevertFailureReason failureReason) {
        int inserted = walletOrderRefundRevertRepository.insertIfAbsent(refund.getRefundId(), refund.getOrderId(),
                refund.getBuyerId(), refund.getAmount(), status.name(),
                failureReason == null ? null : failureReason.name(), LocalDateTime.now(clock));
        if (inserted == 0) {
            WalletOrderRefundRevert processed = walletOrderRefundRevertRepository.findForShareByRefundId(refund.getRefundId())
                    .orElseThrow(() -> new IllegalStateException(
                            "환불 되돌리기 처리 기록 넣기 결과가 0건인데 기록이 없습니다. 중복이 아닌 이유로 넣기가 무시됐을 수 있습니다. refundId="
                                    + refund.getRefundId()));
            return OrderRefundRevertResult.alreadyProcessed(processed);
        }
        return OrderRefundRevertResult.processed(refund.getRefundId(), refund.getOrderId(), refund.getBuyerId(),
                refund.getAmount(), status, failureReason);
    }
}
