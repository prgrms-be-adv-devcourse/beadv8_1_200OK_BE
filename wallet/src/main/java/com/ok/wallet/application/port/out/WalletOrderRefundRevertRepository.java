package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.WalletOrderRefundRevert;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 환불 되돌리기 처리 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface WalletOrderRefundRevertRepository {

    // 같은 환불의 되돌리기 기록이 없을 때만 넣고, 넣었으면 1, 이미 있었으면 0을 돌려준다. 반환 처리 기록과 같은 방식이다.
    // 구현(INSERT IGNORE)이 중복 외 오류도 넘기므로 값 검증은 호출하는 쪽이 먼저 한다.
    int insertIfAbsent(Long refundId, Long orderId, Long buyerId, long amount, String status, String failureReason,
                       LocalDateTime now);

    // 되돌리기 기록을 잠그지 않고 읽는다. 기록은 한 번 들어가면 바뀌지 않는다.
    Optional<WalletOrderRefundRevert> findByRefundId(Long refundId);

    // 이미 있는 되돌리기 기록을 최신 커밋 데이터로 읽는다. 기록이 있을 때(insertIfAbsent가 0)만 부른다.
    Optional<WalletOrderRefundRevert> findForShareByRefundId(Long refundId);
}
