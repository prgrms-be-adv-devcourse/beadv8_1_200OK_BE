package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.SellerWallet;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 판매자 지갑 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface SellerWalletRepository {
    boolean existsByMemberId(Long memberId);

    // 잔액 조회용 일반 조회 - 잠그지 않는다. 잔액을 바꾸는 곳에서는 findForUpdateByMemberId를 쓴다.
    Optional<SellerWallet> findByMemberId(Long memberId);

    // 지갑이 없으면 저장하고, 이미 있으면 아무것도 하지 않는다.
    // 중복일 때만 넘어가고 다른 오류(null 등)는 그대로 실패한다.
    void insertIfAbsent(Long memberId, LocalDateTime now);

    // 잔액 변경용 잠금 조회 - 트랜잭션이 끝날 때까지 다른 트랜잭션의 잠금 조회를 막는다.
    // 최신 커밋 데이터를 읽어, 다른 트랜잭션이 방금 만든 지갑도 볼 수 있다.
    Optional<SellerWallet> findForUpdateByMemberId(Long memberId);
}
