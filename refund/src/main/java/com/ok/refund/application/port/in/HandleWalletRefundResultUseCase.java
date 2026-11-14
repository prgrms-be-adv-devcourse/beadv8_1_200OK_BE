package com.ok.refund.application.port.in;

/**
 * 지갑이 보낸 예치금 반환 결과(완료·실패)를 받아 환불에 반영하는 입력 포트.
 * 상태나 금액·주문이 맞지 않는 메시지는 예외 없이 실패를 기록하거나 로그만 남기고 넘긴다. 예외를 던지면 메시지 오류 처리기가
 * 같은 메시지를 다시 시도해 어차피 같은 결과인 일로 다시 시도 줄을 막기 때문이다.
 * 재고 복구 실패(PRODUCT)가 이미 기록된 환불은 지갑 결과로 실패 단계를 바꾸지 않는다. 실패 단계로 다시 시도(#545)·되돌리기(#546)를 정하기 때문이다.
 */
public interface HandleWalletRefundResultUseCase {

    /**
     * 지갑 반환 완료를 반영한다. 처리 중이고 금액·주문이 환불과 같으면 재고를 되돌린 뒤 환불을 완료로 바꾼다.
     * 이미 완료됐거나 처리 중이 아닌 환불, 없는 환불은 넘기고, 금액·주문이 다르면 완료하지 않고 {@code RESULT_MISMATCH}를 남긴다.
     *
     * @throws com.ok.common.exception.RestApiException 재고 복구가 실패하면 {@code PRODUCT_API_FAILED}
     *                                                  (PRODUCT 단계 실패를 기록하고 다시 던져 오류 처리기가 다시 시도한다)
     */
    void completeAfterWalletRefund(WalletRefundCompletedCommand command);

    /**
     * 지갑 반환 실패를 환불에 남긴다. 처리 중이면 WALLET 단계와 지갑이 알려 준 이유를 기록하고 처리 중으로 둔다.
     * 주문이 환불과 다르면 지갑 이유 대신 {@code RESULT_MISMATCH}를 남긴다(금액은 비교하지 않는다).
     * 같은 이유가 이미 기록돼 있거나, 처리 중이 아니거나, 재고 복구 실패가 이미 기록돼 있으면 넘긴다.
     */
    void recordWalletRefundFailure(WalletRefundFailedCommand command);
}
