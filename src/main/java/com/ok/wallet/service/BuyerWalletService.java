package com.ok.wallet.service;

import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BuyerWalletService {
    private final BuyerWalletRepository buyerWalletRepository;

    /**
     * 구매자 지갑을 잠금 조회로 반환한다. 지갑이 없다면 생성한 뒤 반환한다
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     */
    @Transactional
    public BuyerWallet getOrCreate(Long memberId) {
        if (!buyerWalletRepository.existsByMemberId(memberId)) {
            buyerWalletRepository.insertIfAbsent(memberId, LocalDateTime.now());
        }

        // 사용자가 일으킬 수 없는 코드나 DB의 이상 : 표준 예외
        return buyerWalletRepository.findForUpdateByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException("구매자 지갑 생성 후 조회에 실패했습니다. memberId=" + memberId));
    }
}
