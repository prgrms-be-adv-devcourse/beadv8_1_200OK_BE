package com.ok.refund.application.service;

import com.ok.refund.application.port.in.GetBuyerRefundsUseCase;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.RefundPersistencePort;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 본인의 환불 요청 목록을 조회한다. DB 조회만 하므로 읽기 전용 구간 하나로 끝낸다.
 */
@Service
public class GetBuyerRefundsService implements GetBuyerRefundsUseCase {

    private final RefundPersistencePort refundPersistencePort;
    private final TransactionTemplate readOnlyTransaction;

    public GetBuyerRefundsService(RefundPersistencePort refundPersistencePort, PlatformTransactionManager transactionManager) {
        this.refundPersistencePort = refundPersistencePort;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Override
    public Page<RefundResult> getBuyerRefunds(Long buyerId, int page) {
        return readOnlyTransaction.execute(status ->
                refundPersistencePort.findAllByBuyerId(buyerId, page).map(RefundResult::from));
    }
}
