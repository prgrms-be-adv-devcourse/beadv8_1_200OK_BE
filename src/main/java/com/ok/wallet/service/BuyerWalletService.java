package com.ok.wallet.service;

import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.repository.BuyerWalletLedgerRepository;
import com.ok.wallet.repository.BuyerWalletRepository;
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
     * 승인된 충전 금액을 구매자 지갑에 반영하고 원장에 기록한다.
     * 같은 근거 키로 이미 반영했다면 아무것도 하지 않는다.
     */
    @Transactional
    public void charge(Long memberId, long amount, String referenceKey) {
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
     * 주문 결제 금액을 구매자 지갑에서 차감하고 원장에 기록한다.
     * 근거 키(PAYMENT:{orderId})는 여기서 만들어, 호출하는 쪽이 규칙을 몰라도 되고 같은 주문은 한 번만 차감된다.
     * 지갑을 잠근 뒤 잔액을 확인하므로, 같은 회원의 결제·충전이 동시에 와도 순서대로 처리된다.
     * 지갑이 없으면 잔액 0원인 지갑을 만든 뒤 판단하므로 잔액 부족이 된다.
     */
    @Transactional
    public OrderPaymentResult payForOrder(Long memberId, Long orderId, long amount) {
        String referenceKey = "PAYMENT:" + orderId;
        BuyerWallet wallet = getOrCreate(memberId);
        // 이 지갑에 이미 반영한 주문이면 넘어간다. 다른 지갑에 같은 키가 있으면 저장 시 유니크 제약으로 실패해 오류를 드러낸다
        if (buyerWalletLedgerRepository.existsByBuyerWalletIdAndReferenceKey(wallet.getId(), referenceKey)) {
            log.info("이미 결제된 주문이라 차감을 건너뜁니다. buyerWalletId={}, orderId={}", wallet.getId(), orderId);
            return OrderPaymentResult.ALREADY_PAID;
        }
        if (!wallet.hasEnoughBalance(amount)) {
            return OrderPaymentResult.INSUFFICIENT_BALANCE;
        }
        BuyerWalletLedger ledger = wallet.pay(amount, referenceKey);
        buyerWalletLedgerRepository.save(ledger);
        return OrderPaymentResult.PAID;
    }
}
