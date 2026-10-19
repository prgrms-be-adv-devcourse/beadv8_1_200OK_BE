package com.ok.payout.service;

import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.repository.PayoutWalletDeductionRepository;
import com.ok.payout.service.dto.WalletPaymentCompletedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutWalletDeductionService {

    private final PayoutWalletDeductionRepository repository;

    // 지갑 차감 기록을 저장한다. 비교(교차 검증)는 하지 않고 #67 배치에서 함
    // 같은 이벤트가 이미 처리됐으면 무시한다 (재전달·재시작 시 멱등)
    // 동시에 두 번 처리되는 드문 경우는 DB UK(order_id)가 중복 저장을 막고, 실패한 쪽은 재처리 시 이 확인에서 무시됨
    @Transactional
    public void saveDeduction(WalletPaymentCompletedCommand command) {
        if (repository.existsByOrderId(command.orderId())) {
            log.info("이미 처리된 지갑 차감 이벤트, 무시. orderId={}", command.orderId());
            return;
        }
        repository.save(PayoutWalletDeduction.create(
                command.orderId(), command.buyerId(), command.amount(), command.paidAt()));
    }
}
