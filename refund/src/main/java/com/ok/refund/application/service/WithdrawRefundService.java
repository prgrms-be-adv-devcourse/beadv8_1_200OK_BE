package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.in.WithdrawRefundUseCase;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.domain.Refund;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 구매자가 본인 환불 요청을 승인 전에 거둔다. 다른 모듈을 부르지 않아 잠금·변경·저장을 한 구간에서 끝낸다.
 */
@Service
public class WithdrawRefundService implements WithdrawRefundUseCase {

    private final RefundPersistencePort refundPersistencePort;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WithdrawRefundService(RefundPersistencePort refundPersistencePort, Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.refundPersistencePort = refundPersistencePort;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 구매자가 본인 요청을 승인 전에 거둔다. 판매자 결정과 겹칠 때를 위해 승인({@link DecideRefundService})과 같은 방식으로 행을 잠근다.
     *
     * @throws RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    @Override
    public RefundResult withdraw(Long refundId, Long buyerId) {
        return transaction.execute(status -> {
            Refund refund = refundPersistencePort.findByIdForUpdate(refundId)
                    .filter(found -> found.isOwnedByBuyer(buyerId))
                    .orElseThrow(() -> new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));
            refund.withdraw(LocalDateTime.now(clock));
            refundPersistencePort.update(refund);
            return RefundResult.from(refund);
        });
    }
}
