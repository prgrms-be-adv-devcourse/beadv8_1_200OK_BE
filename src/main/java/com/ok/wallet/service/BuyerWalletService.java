package com.ok.wallet.service;

import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BuyerWalletService {
    private final BuyerWalletRepository buyerWalletRepository;

    /**
     * 구매자 지갑을 생성한다. 이미 존재하면 아무것도 하지 않는다.
     * 동시 중복 처리로 unique 제약 위반이 발생하면 예외를 그대로 전파한다.
     * 이벤트는 미완료로 남아 재처리되며, 재처리 시에는 이미 존재하는 지갑이 확인되어 정상 완료된다.
     * 예외를 잡아 무시하면 중복이 아닌 다른 저장 실패까지 삼킬 수 있어 전파를 유지한다.
     */
    @Transactional
    public void create(Long memberId) {
        if (buyerWalletRepository.existsByMemberId(memberId)) {
            return;
        }
        buyerWalletRepository.save(BuyerWallet.create(memberId));
    }
}
