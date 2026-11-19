package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.WalletOrderRefund;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 환불 반환 처리 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface WalletOrderRefundRepository {

    // 같은 환불의 처리 기록이 없을 때만 넣고, 넣었으면 1, 이미 있었으면 0을 돌려준다. 주문 결제 처리 기록과 같은 방식이다(#360).
    // 구현(INSERT IGNORE)이 중복 외 오류도 넘기므로 값 검증은 호출하는 쪽이 먼저 한다.
    int insertIfAbsent(Long refundId, Long orderId, Long buyerId, long amount, String status, String failureReason,
                       LocalDateTime now);

    // 처리 기록을 잠그지 않고 읽는다. 기록은 한 번 들어가면 바뀌지 않아, 있으면 그 값이 끝까지 맞다.
    // 없다는 답은 읽은 순간의 것이라, 없을 때 무언가를 정하려면 insertIfAbsent로 다시 확인한다.
    Optional<WalletOrderRefund> findByRefundId(Long refundId);

    // 이미 있는 처리 기록을 최신 커밋 데이터로 읽는다. 기록이 있을 때(insertIfAbsent가 0)만 부른다.
    Optional<WalletOrderRefund> findForShareByRefundId(Long refundId);

    // 주문에 이미 돌려준 금액의 합계. 거절한 기록과 되돌리기로 다시 가져온 기록은 세지 않는다. 기록이 없으면 0.
    // 다시 가져온 환불을 세면 같은 상품을 새 환불로 돌려줄 때 합계 초과로 막힌다.
    // 최신 커밋 데이터로 읽는다. 일반 조회는 지갑 잠금을 기다리기 전에 잡힌 스냅샷을 보므로,
    // 같은 주문의 다른 환불이 방금 돌려준 금액을 빼고 더해 결제 금액보다 많이 돌려줄 수 있다.
    long sumCompletedAmountForShareByOrderId(Long orderId);
}
