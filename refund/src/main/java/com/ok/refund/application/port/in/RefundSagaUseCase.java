package com.ok.refund.application.port.in;

/**
 * 승인 뒤 단계(주문 취소 반영 → 지갑 반환 요청 → 지갑 결과 → 재고 복구 → 완료)를 진행하는 지휘자 입력 포트다(#575).
 * 승인 서비스는 승인 구간을 커밋한 뒤 시작만 맡기고, 지갑 결과 메시지도 이 포트로 들어온다. 다음 단계를 정하고 다른 모듈을 부르는 곳은 이 포트의 구현 하나다.
 * 지갑 결과는 상태나 금액·주문·구매자가 맞지 않으면 예외 없이 기록하거나 로그만 남기고 넘긴다. 예외를 던지면 메시지 오류 처리기가
 * 같은 메시지를 다시 시도해 어차피 같은 결과인 일로 다시 시도 줄을 막기 때문이다.
 * 재고 단계가 이미 시작된 환불은 지갑 결과로 지갑 실패를 남기지 않는다. 어디까지 왔는지로 다시 시도(#576)·되돌리기(#546)를 정하기 때문이다.
 */
public interface RefundSagaUseCase {

    /**
     * 승인 구간이 커밋된 뒤의 단계를 이어 간다. 처리 중이 된 환불만 진행하고, 자동 거절 등으로 처리 중이 아니면 받은 결과를 그대로 돌려준다.
     * 주문이 반영을 거절하면 거절로 끝내고, 돌려줄 돈이 있으면 지갑 반환 요청을 보낸 데서 멈추고, 0원이면 재고 복구 → 완료까지 간다.
     *
     * @param approved 승인 구간이 돌려준 결과. 주문·지갑·재고 호출 값을 여기서 꺼낸다
     * @return 마지막 상태(돈이 있으면 처리 중, 0원이면 완료, 주문 거절이면 거절)
     * @throws com.ok.common.exception.RestApiException 주문 취소 반영이 실패하면 {@code ORDER_API_FAILED},
     *                                                  0원 환불의 재고 복구가 실패하면 {@code PRODUCT_API_FAILED} (처리 중으로 남는다)
     * @throws IllegalStateException                    지갑 반환 요청을 보내지 못했으면 (처리 중으로 남는다, 주문은 이미 반영됐다)
     */
    RefundResult startAfterApproval(RefundResult approved);

    /**
     * 지갑 반환 완료를 반영한다. 처리 중이고 금액·주문·구매자가 환불과 같으면 재고를 되돌린 뒤 환불을 완료로 바꾼다.
     * 이미 완료됐거나 처리 중이 아닌 환불, 없는 환불은 넘기고, 금액·주문·구매자가 다르면 완료하지 않고 {@code RESULT_MISMATCH}를 남긴다.
     *
     * @throws com.ok.common.exception.RestApiException 재고 복구가 실패하면 {@code PRODUCT_API_FAILED}
     *                                                  (PRODUCT 단계 실패를 기록하고 다시 던져 오류 처리기가 다시 시도한다)
     */
    void completeAfterWalletRefund(WalletRefundCompletedCommand command);

    /**
     * 지갑 반환 실패를 환불에 남긴다. 처리 중이면 WALLET 단계와 지갑이 알려 준 이유를 기록하고 처리 중으로 둔다.
     * 금액·주문·구매자가 환불과 다르면 지갑 이유 대신 {@code RESULT_MISMATCH}를 남긴다.
     * 같은 이유가 이미 기록돼 있거나, 처리 중이 아니거나, 재고 단계가 이미 시작됐으면 넘긴다.
     */
    void recordWalletRefundFailure(WalletRefundFailedCommand command);

    /**
     * 환불 번호만으로 승인 뒤 단계를 이어 간다(#616). 지휘 작업(알람, #576)이 멈춘 환불을 다시 진행할 때 쓴다.
     * 단계 줄을 보고 다음 할 일을 정해 그 단계부터 진행한다. 할 차례인 단계는 다시 시작해(시도 횟수 +1) 부른다.
     * 시도 중이거나 지갑 결과를 기다리는 중이면 아무것도 하지 않고, 단계 줄이 없거나 멈춘 단계가 있거나 처리 중이 아닌 환불이면 WARN만 남긴다.
     * 시도 중이 오래 멈춘 줄을 다시 시작하는 일(#576)과 결과 대기 시간 초과(#577)는 아직 하지 않는다.
     *
     * @return 진행한 뒤의 환불 상태
     * @throws com.ok.common.exception.RestApiException 다른 모듈 호출이 실패하면 그 에러 코드(그 단계 줄에 실패를 남기고 그대로 올린다.
     *                                                  환불마다 예외를 잡고 다음 환불로 넘어가는 일은 부르는 쪽이 한다)
     * @throws IllegalStateException                    환불이 없으면, 열린 트랜잭션 안에서 부르면
     */
    RefundResult resume(Long refundId);
}
