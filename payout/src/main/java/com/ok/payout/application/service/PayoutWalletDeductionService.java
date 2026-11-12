package com.ok.payout.application.service;

import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.domain.PayoutWalletDeductionConflict;
import com.ok.payout.application.port.out.PayoutWalletDeductionConflictRepository;
import com.ok.payout.application.port.out.PayoutWalletDeductionRepository;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import com.ok.payout.application.port.in.PayoutWalletDeductionUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutWalletDeductionService implements PayoutWalletDeductionUseCase {

    private final PayoutWalletDeductionRepository repository;
    private final PayoutWalletDeductionConflictRepository conflictRepository;

    // 지갑 차감 기록을 저장한다. 비교(교차 검증)는 하지 않고 #67 배치에서 함
    // 같은 이벤트가 이미 처리됐으면 무시한다 (재전달·재시작 시 멱등)
    // 동시에 두 번 처리되는 드문 경우는 DB UK(order_id)가 중복 저장을 막고, 실패한 쪽은 재처리 시 이 확인에서 무시됨
    //
    // 이미 기록이 있는 주문에 금액이 다른 이벤트가 오면 (#391)
    //   저장된 차감 금액은 바꾸지 않음: 먼저 온 값을 기준으로 정산 검사를 해 왔고, 어느 쪽이 맞는지는 이 이벤트만으로 알 수 없음
    //   대신 충돌 표에 남기고 WARN → 초과 청구 실패 상세에 "지갑 금액 충돌 있음"으로 나와 원인을 찾는 근거가 됨
    //   같은 (주문, 받은 금액) 충돌이 다시 오면(Kafka 재전달) 저장하지 않음
    //     동시에 두 번 처리되면 둘 다 확인을 통과할 수 있는데, 이때는 DB UK가 한쪽을 막고 막힌 쪽은 예외로 롤백됨
    //     → Kafka 오류 처리기가 다시 보내면 그때는 이 확인에 걸려 무시됨 (차감 기록 UK와 같은 방식)
    @Transactional
    @Override
    public void saveDeduction(WalletPaymentCompletedCommand command) {
        Optional<PayoutWalletDeduction> stored = repository.findByOrderId(command.orderId());
        if (stored.isEmpty()) {
            repository.save(PayoutWalletDeduction.create(
                    command.orderId(), command.buyerId(), command.amount(), command.paidAt()));
            return;
        }

        Long storedAmount = stored.get().getAmount();
        if (storedAmount.equals(command.amount())) {
            log.info("이미 처리된 지갑 차감 이벤트, 무시. orderId={}", command.orderId());
            return;
        }

        if (conflictRepository.existsByOrderIdAndReceivedAmount(command.orderId(), command.amount())) {
            log.info("이미 기록된 지갑 차감 금액 충돌, 무시. orderId={}, receivedAmount={}",
                    command.orderId(), command.amount());
            return;
        }
        conflictRepository.save(PayoutWalletDeductionConflict.create(
                command.orderId(), storedAmount, command.amount()));
        // 팀 로그 규칙(service.md "로그"): 처리는 계속되지만 사람이 확인해야 하는 이상 상황이라 WARN
        log.warn("지갑 차감 금액 충돌, 저장된 금액 유지. orderId={}, storedAmount={}, receivedAmount={}",
                command.orderId(), storedAmount, command.amount());
    }
}
