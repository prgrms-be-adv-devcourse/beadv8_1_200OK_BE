package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.BuyerWallet;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 구매자 지갑 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface BuyerWalletRepository {
    boolean existsByMemberId(Long memberId);

    // 지갑이 없으면 저장하고, 이미 있으면 아무것도 하지 않는다.
    // 중복일 때만 넘어가고 다른 오류(null 등)는 그대로 실패한다.
    void insertIfAbsent(Long memberId, LocalDateTime now);

    // 잔액 변경용 잠금 조회 - 트랜잭션이 끝날 때까지 다른 트랜잭션의 잠금 조회를 막는다.
    // 최신 커밋 데이터를 읽어, 다른 트랜잭션이 방금 만든 지갑도 볼 수 있다.
    Optional<BuyerWallet> findForUpdateByMemberId(Long memberId);

    // 잔액을 읽기만 할 때 쓴다. 잠금 조회(findForUpdateByMemberId)와 달리 다른 트랜잭션을 기다리게 하지 않는다.
    Optional<BuyerWallet> findByMemberId(Long memberId);
}
