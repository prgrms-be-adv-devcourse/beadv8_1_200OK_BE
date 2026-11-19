package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.WalletOrderPayment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 주문 결제 처리 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface WalletOrderPaymentRepository {

    // 같은 주문의 처리 기록이 없을 때만 넣고, 넣었으면 1, 이미 있었으면 0을 돌려준다(왜 넣기로 판단하는지는 payForOrder, #360).
    // 구현(INSERT IGNORE)이 중복 외 오류도 넘기므로 값 검증은 호출하는 쪽이 먼저 한다.
    // 주문 요약은 없으면 null로 넘긴다(만료로 먼저 남기는 기록).
    int insertIfAbsent(Long orderId, Long buyerId, long amount, String orderSummary, String status, LocalDateTime now);

    // 이미 있는 처리 기록을 최신 커밋 데이터로 읽는다. 일반 조회는 스냅샷 때문에 다른 트랜잭션이 방금 넣은 기록을 못 볼 수 있다.
    // 기록이 있을 때(insertIfAbsent가 0)만 부르므로 그 행만 잠긴다. 없는 행을 잠그면 빈 구간까지 잠겨 다른 주문의 기록 넣기를 막는다
    Optional<WalletOrderPayment> findForShareByOrderId(Long orderId);

    // 잠그지 않고 읽는다. 처리 기록은 바뀌지 않아 환불 반환처럼 결제가 끝난 뒤에 읽을 때는 최신 값과 같다.
    // 기록이 없을 수도 있는 조회라 잠금 조회를 쓰면 빈 구간까지 잠긴다
    Optional<WalletOrderPayment> findByOrderId(Long orderId);

    // 이용 내역 한 페이지의 줄에 주문 요약을 붙이려고 여러 주문의 처리 기록을 한 번에 읽는다. 없는 주문은 빠진다.
    List<WalletOrderPayment> findAllByOrderIdIn(Collection<Long> orderIds);
}
