package com.ok.wallet.repository;

import com.ok.wallet.domain.BuyerWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface BuyerWalletRepository extends JpaRepository<BuyerWallet, Long> {
    boolean existsByMemberId(Long memberId);

    // 지갑이 없으면 저장하고, 이미 있으면 아무것도 하지 않는다.
    // 중복일 때만 넘어가고 다른 오류(null 등)는 그대로 실패한다.
    // MySQL 전용 문법이라 네이티브 쿼리로 작성하며, BaseTime을 거치지 않으므로 생성 시각을 직접 받는다.
    // 컬럼을 직접 나열하므로 BuyerWallet·BaseTime에 NOT NULL 컬럼이 추가되면 이 쿼리도 함께 수정해야 한다.
    @Modifying
    @Query(value = """
            INSERT INTO buyer_wallet (member_id, balance, created_at, updated_at)
            VALUES (:memberId, 0, :now, :now)
            ON DUPLICATE KEY UPDATE member_id = member_id
            """, nativeQuery = true)
    void insertIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    // 잔액 변경용 잠금 조회 - 트랜잭션이 끝날 때까지 다른 트랜잭션의 잠금 조회를 막는다.
    // 최신 커밋 데이터를 읽어, 다른 트랜잭션이 방금 만든 지갑도 볼 수 있다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BuyerWallet> findForUpdateByMemberId(Long memberId);
}
