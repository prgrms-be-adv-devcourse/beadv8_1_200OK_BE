package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.WalletSchema;
import com.ok.wallet.domain.BuyerWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface BuyerWalletJpaRepository extends JpaRepository<BuyerWallet, Long> {

    boolean existsByMemberId(Long memberId);

    // MySQL 전용 문법이라 네이티브 쿼리로 작성하며, BaseTime을 거치지 않으므로 생성 시각을 직접 받는다.
    // 컬럼을 직접 나열하므로 BuyerWallet·BaseTime에 NOT NULL 컬럼이 추가되면 이 쿼리도 함께 수정해야 한다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
    @Modifying
    @Query(value = "INSERT INTO " + WalletSchema.NAME + """
            .buyer_wallet (member_id, balance, created_at, updated_at)
            VALUES (:memberId, 0, :now, :now)
            ON DUPLICATE KEY UPDATE member_id = member_id
            """, nativeQuery = true)
    void insertIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BuyerWallet> findForUpdateByMemberId(Long memberId);

    Optional<BuyerWallet> findByMemberId(Long memberId);
}
