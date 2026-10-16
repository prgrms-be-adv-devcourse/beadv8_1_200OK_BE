package com.ok.wallet.service;

import com.ok.wallet.client.toss.TossConfirmResult;
import com.ok.wallet.client.toss.TossPaymentsClient;
import com.ok.wallet.service.dto.ChargeConfirmResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 충전 승인 흐름을 조율한다. 이 클래스에는 @Transactional을 두지 않는다.
 * 토스 응답을 기다리는 동안 DB 커넥션을 잡고 있으면 토스가 느려질 때 커넥션 풀이 고갈되어
 * 결제와 무관한 API까지 멈추므로, 토스 호출은 두 트랜잭션 사이(트랜잭션 밖)에서 한다.
 */
@Service
@RequiredArgsConstructor
public class ChargeConfirmService {
    private final ChargeTransactionService chargeTransaction;
    private final TossPaymentsClient tossPaymentsClient;

    public ChargeConfirmResult confirm(Long memberId, String chargeNo, String paymentKey, long amount) {
        // [트랜잭션 1] 소유자·금액·한도 확인 후 처리 중으로 전이
        ChargeTransactionService.ConfirmStart start = chargeTransaction.startConfirm(memberId, chargeNo, amount);
        if (!start.started()) {
            // 이미 다른 요청이 승인을 시작했거나 끝난 건: 토스를 다시 호출하지 않는다
            return start.current();
        }

        // [트랜잭션 밖] 토스 승인
        TossConfirmResult result = tossPaymentsClient.confirm(paymentKey, chargeNo, amount);

        // [트랜잭션 2] 결과 반영
        return switch (result) {
            case TossConfirmResult.Approved approved ->
                    chargeTransaction.complete(memberId, chargeNo, approved.paymentKey());
            case TossConfirmResult.Rejected rejected ->
                    chargeTransaction.failApproval(memberId, chargeNo, rejected.code(), rejected.message());
            // 승인 여부를 알 수 없으므로 처리 중으로 두고, 복구 스케줄러(#135)가 토스에 확인한다
            case TossConfirmResult.Unknown unknown -> chargeTransaction.currentStatus(memberId, chargeNo);
        };
    }
}
