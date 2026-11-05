package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.WalletOrderPayment;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 주문 결제 처리 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface WalletOrderPaymentRepository {

    // 같은 주문의 처리 기록이 없을 때만 넣고, 넣었으면 1, 이미 있었으면 0을 돌려준다(왜 넣기로 판단하는지는 payForOrder, #360).
    // 구현(INSERT IGNORE)이 중복 외 오류도 넘기므로 값 검증은 호출하는 쪽이 먼저 한다.
    int insertIfAbsent(Long orderId, Long buyerId, long amount, String status, LocalDateTime now);

    // 이미 있는 처리 기록을 최신 커밋 데이터로 읽는다. 일반 조회는 스냅샷 때문에 다른 트랜잭션이 방금 넣은 기록을 못 볼 수 있다.
    // 기록이 있을 때(insertIfAbsent가 0)만 부르므로 그 행만 잠긴다. 없는 행을 잠그면 빈 구간까지 잠겨 다른 주문의 기록 넣기를 막는다
    Optional<WalletOrderPayment> findForShareByOrderId(Long orderId);
}
