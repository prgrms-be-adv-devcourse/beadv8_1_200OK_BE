package com.ok.wallet.service;

import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.domain.WalletOrderPayment;
import com.ok.wallet.domain.WalletOrderPaymentStatus;
import com.ok.wallet.repository.BuyerWalletLedgerRepository;
import com.ok.wallet.repository.BuyerWalletRepository;
import com.ok.wallet.repository.WalletOrderPaymentRepository;
import com.ok.wallet.service.dto.BuyerBalanceResult;
import com.ok.wallet.service.dto.OrderPaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerWalletService {
    private final BuyerWalletRepository buyerWalletRepository;
    private final BuyerWalletLedgerRepository buyerWalletLedgerRepository;
    private final WalletOrderPaymentRepository walletOrderPaymentRepository;
    // 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
    // 지갑 생성 시각을 LocalDateTime.now()로 직접 만들면 실행 환경의 시간대를 따라가고, 테스트에서 값을 고정할 수 없다 (#93)
    private final Clock clock;

    /**
     * 구매자 지갑을 잠금 조회로 반환한다. 지갑이 없다면 생성한 뒤 반환한다.
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     * 반환된 지갑은 호출한 트랜잭션이 끝날 때까지 잠겨 있으므로 잔액 조회처럼 읽기만 하는 곳에는 쓰지 않는다.
     * 회원가입 리스너처럼 지갑이 있는지만 보장하면 되는 짧은 트랜잭션에서는, 생성 경로를 하나로 두기 위해 이 메서드를 쓴다.
     */
    @Transactional
    public BuyerWallet getOrCreate(Long memberId) {
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

    /**
     * 현재 잔액을 잠그지 않고 조회한다. 지갑이 아직 없으면 0으로 본다.
     * 잔액을 바꾸지 않는 확인용이며, 잔액 변경 전 확인은 잠금 조회 후에 다시 해야 한다.
     */
    @Transactional(readOnly = true)
    public long getBalance(Long memberId) {
        return buyerWalletRepository.findByMemberId(memberId)
                .map(BuyerWallet::getBalance)
                .orElse(0L);
    }

    /**
     * 현재 잔액과 잔액 보유 한도를 함께 조회한다. 지갑이 아직 없으면 잔액은 0으로 본다.
     * 프론트가 충전 가능 금액(한도 - 잔액)을 계산할 수 있도록 한도를 같이 돌려준다.
     */
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
     */
    @Transactional
    public OrderPaymentResult payForOrder(Long memberId, Long orderId, long amount) {
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
        int inserted = walletOrderPaymentRepository.insertIfAbsent(
                orderId, memberId, amount, status.name(), LocalDateTime.now(clock));
        if (inserted == 0) {
            // 이미 처리된 주문이다. 기록은 넣기를 거쳐 있음이 확인됐으므로 없을 수 없다. 없다면 코드·DB의 이상이라 500으로 드러낸다
            WalletOrderPayment processed = walletOrderPaymentRepository.findForShareByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException("주문 결제 처리 기록 조회에 실패했습니다. orderId=" + orderId));
            return processed.isPaid() ? OrderPaymentResult.ALREADY_PAID : OrderPaymentResult.ALREADY_FAILED;
        }

        if (!enoughBalance) {
            return OrderPaymentResult.INSUFFICIENT_BALANCE;
        }
        // 원장 근거 키는 마지막 안전장치로 남긴다 (같은 주문이 두 번 기록되려 하면 유니크 제약이 막는다)
        // 키 규칙은 이용 내역 조회와 같은 곳(PaymentReferenceKey)에서 만든다
        buyerWalletLedgerRepository.save(wallet.pay(amount, PaymentReferenceKey.of(orderId)));
        return OrderPaymentResult.PAID;
    }
}
