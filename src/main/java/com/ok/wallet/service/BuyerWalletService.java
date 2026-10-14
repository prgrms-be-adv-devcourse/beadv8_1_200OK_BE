package com.ok.wallet.service;

import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.repository.BuyerWalletLedgerRepository;
import com.ok.wallet.repository.BuyerWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BuyerWalletService {
    private final BuyerWalletRepository buyerWalletRepository;
    private final BuyerWalletLedgerRepository buyerWalletLedgerRepository;

    /**
     * 구매자 지갑을 잠금 조회로 반환한다. 지갑이 없으면 생성한 뒤 반환한다.
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     * 반환된 지갑은 호출한 트랜잭션이 끝날 때까지 잠겨 있으므로, 잔액을 바꾸지 않는 호출에는 쓰지 않는다.
     */
    @Transactional
    public BuyerWallet getOrCreate(Long memberId) {
        if (!buyerWalletRepository.existsByMemberId(memberId)) {
            // TODO: 공통 Clock 빈이 생기면 LocalDateTime.now(clock)으로 변경 (.claude/rules/service.md)
            buyerWalletRepository.insertIfAbsent(memberId, LocalDateTime.now());
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
        // 지갑을 잠근 뒤에 확인하므로 같은 지갑의 요청은 순서대로 이 확인을 거친다.
        // 같은 근거 키가 동시에 확인을 통과하더라도 원장의 유니크 제약이 두 번째 저장을 막는다.
        if (buyerWalletLedgerRepository.existsByReferenceKey(referenceKey)) {
            return;
        }
        BuyerWalletLedger ledger = wallet.charge(amount, referenceKey);
        buyerWalletLedgerRepository.save(ledger);
    }
}
