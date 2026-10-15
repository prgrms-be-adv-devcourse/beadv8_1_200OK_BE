package com.ok.wallet.service;

import com.ok.wallet.domain.SellerWallet;
import com.ok.wallet.repository.SellerWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SellerWalletService {
    private final SellerWalletRepository sellerWalletRepository;

    /**
     * 판매자 지갑을 잠금 조회로 반환한다. 지갑이 없다면 생성한 뒤 반환한다.
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     * 반환된 지갑은 호출한 트랜잭션이 끝날 때까지 잠겨 있으므로, 잔액을 바꾸지 않는 호출에는 쓰지 않는다.
     * 판매자 승인 여부는 확인하지 않는다. 정산금 입금처럼 승인된 판매자임이 이미 보장된 경로에서만 호출한다.
     */
    @Transactional
    public SellerWallet getOrCreate(Long memberId) {
        if (!sellerWalletRepository.existsByMemberId(memberId)) {
            // TODO: 공통 Clock 빈이 생기면 LocalDateTime.now(clock)으로 변경
            sellerWalletRepository.insertIfAbsent(memberId, LocalDateTime.now());
        }
        // 바로 앞에서 저장했거나 존재를 확인했으므로 여기서 없을 수 없다.
        // 없다면 사용자 입력이 아닌 코드·DB의 이상이므로 500으로 드러나도록 IllegalStateException을 던진다.
        return sellerWalletRepository.findForUpdateByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException("판매자 지갑 생성 후 조회에 실패했습니다. memberId=" + memberId));
    }
}
