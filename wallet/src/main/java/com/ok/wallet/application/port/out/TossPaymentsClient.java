package com.ok.wallet.application.port.out;

import com.ok.wallet.application.dto.TossCancelResult;
import com.ok.wallet.application.dto.TossConfirmResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;

/**
 * 토스페이먼츠 결제 승인·조회·취소 출력 포트. 구현은 {@code adapter.out.toss}에 있다. 트랜잭션 밖에서 호출해야 한다.
 * 응답이 확실하지 않으면 불명확(알 수 없음)으로 두어, 호출하는 쪽이 충전 건을 잘못 바꾸지 않게 한다.
 */
public interface TossPaymentsClient {

    /**
     * 결제를 승인한다. 같은 충전 건으로 다시 호출해도 토스가 한 번만 승인하도록 충전 번호를 멱등 키로 쓴다.
     *
     * @param paymentKey 토스가 결제창에서 발급한 결제 키
     * @param chargeNo   충전 번호 (토스의 orderId)
     * @param amount     서버에 저장된 충전 금액
     */
    TossConfirmResult confirm(String paymentKey, String chargeNo, long amount);

    /**
     * 충전 번호로 토스의 결제를 조회한다.
     * 결제 키가 아니라 충전 번호로 묻는 건, 저장된 결제 키는 브라우저가 보낸 값이라 확인되지 않았을 수 있어서다.
     *
     * @param chargeNo 충전 번호 (토스의 orderId)
     */
    TossPaymentLookupResult findPayment(String chargeNo);

    /**
     * 결제를 전액 취소한다.
     * 멱등 키는 호출하는 쪽이 시도마다 새로 만든다. 같은 키로 다시 보내면 토스가 첫 응답을 그대로 돌려주는데,
     * 첫 응답이 일시 오류였다면 다시 시도해도 같은 오류만 받을 수 있어서다. 전액 취소는 토스에서 한 번만 되므로
     * (다시 보내면 이미 취소됨으로 답함) 키를 바꿔도 두 번 취소되지 않는다.
     *
     * @param paymentKey     취소할 결제의 결제 키. 토스 조회로 확인한 값을 넘긴다
     * @param chargeNo       충전 번호 (토스의 orderId). 응답 대조에 쓴다
     * @param cancelReason   취소 사유 (토스 필수, 200자 이하)
     * @param idempotencyKey 이번 시도의 멱등 키 (300자 이하)
     */
    TossCancelResult cancel(String paymentKey, String chargeNo, String cancelReason, String idempotencyKey);
}
