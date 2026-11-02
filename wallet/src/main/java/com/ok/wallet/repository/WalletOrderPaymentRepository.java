package com.ok.wallet.repository;

import com.ok.wallet.WalletSchema;
import com.ok.wallet.domain.WalletOrderPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

// 결제 결과는 한 번 기록하면 바뀌지 않으므로 넣기와 조회만 둔다.
public interface WalletOrderPaymentRepository extends Repository<WalletOrderPayment, Long> {

    // 같은 주문의 처리 기록이 없을 때만 넣는다. 넣었으면 1, 이미 있었으면 0을 돌려준다.
    // "이미 처리했나"를 일반 조회로 판단하면 호출한 트랜잭션의 스냅샷 때문에 먼저 끝난 결제를 못 볼 수 있다(#360).
    // 넣기는 스냅샷이 아니라 유니크 제약(uk_wallet_order_payment_order_id)과 최신 데이터로 판단하므로 이 문제가 없다.
    // ON DUPLICATE KEY UPDATE는 MySQL 드라이버 기본 설정(CLIENT_FOUND_ROWS)에서 중복이어도 1을 돌려줘 넣었는지 구분할 수 없어 IGNORE를 쓴다.
    // IGNORE는 중복 외 오류(NULL, CHECK 위반)도 경고로 넘기므로 값 검증은 호출하는 쪽(BuyerWalletService.payForOrder)이 먼저 한다.
    // BaseTime을 거치지 않으므로 생성 시각을 직접 받고, 엔티티에 NOT NULL 컬럼이 추가되면 이 쿼리도 함께 수정해야 한다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
    @Modifying
    @Query(value = "INSERT IGNORE INTO " + WalletSchema.NAME + """
            .wallet_order_payment (order_id, buyer_id, amount, status, created_at, updated_at)
            VALUES (:orderId, :buyerId, :amount, :status, :now, :now)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId, @Param("buyerId") Long buyerId, @Param("amount") long amount,
                       @Param("status") String status, @Param("now") LocalDateTime now);

    // 이미 있는 처리 기록을 최신 커밋 데이터로 읽는다. 일반 조회는 스냅샷 때문에 다른 트랜잭션이 방금 넣은 기록을 못 볼 수 있다.
    // 기록이 있을 때(insertIfAbsent가 0)만 부르므로 그 행만 잠긴다. 없는 행을 잠그면 빈 구간까지 잠겨 다른 주문의 기록 넣기를 막는다
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<WalletOrderPayment> findForShareByOrderId(Long orderId);
}
