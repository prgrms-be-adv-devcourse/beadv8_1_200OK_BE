package com.ok.refund.application.service;

import com.ok.refund.application.port.in.GetSellerRefundsUseCase;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.domain.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자에게 온 환불 요청 목록을 조회한다. DB 조회만 하므로 읽기 전용 구간 하나로 끝낸다.
 */
@Service
public class GetSellerRefundsService implements GetSellerRefundsUseCase {

    private final RefundPersistencePort refundPersistencePort;
    private final TransactionTemplate readOnlyTransaction;

    public GetSellerRefundsService(RefundPersistencePort refundPersistencePort, PlatformTransactionManager transactionManager) {
        this.refundPersistencePort = refundPersistencePort;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Override
    public Page<RefundResult> getSellerRefunds(Long sellerId, String status, int page) {
        return readOnlyTransaction.execute(transactionStatus -> {
            if (status == null) {
                return refundPersistencePort.findAllBySellerId(sellerId, page).map(RefundResult::from);
            }
            return refundPersistencePort.findAllBySellerIdAndStatus(sellerId, RefundStatus.valueOf(status), page)
                    .map(RefundResult::from);
        });
    }
}
