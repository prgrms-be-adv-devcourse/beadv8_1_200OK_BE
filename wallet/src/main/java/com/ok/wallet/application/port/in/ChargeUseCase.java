package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.ChargeConfirmResult;
import com.ok.wallet.application.dto.ChargeRequestResult;

/**
 * 예치금 충전 요청, 결제창 실패 기록, 충전 상태 조회 유스케이스.
 * 토스 승인은 {@link ChargeConfirmUseCase}가 맡는다.
 */
public interface ChargeUseCase {

    /**
     * 충전을 요청한다. 필수 약관 동의 → 금액 규칙 → 잔액 한도 순서로 확인한다.
     * 동의는 프론트가 충전 버튼을 누를 때 약관 조회 API로 먼저 받고, 여기서는 건너뛴 경우나 그 사이 개정된 경우를 막는다.
     * 잔액 한도는 여기서 잠그지 않고 1차로 확인하며, 정확한 확인은 승인 시점에 지갑을 잠근 뒤 처리 중 충전까지 합쳐서 한다.
     */
    ChargeRequestResult request(Long memberId, long amount);

    /**
     * 토스 결제창에서 실패한 충전 건을 실패로 바꾼다.
     */
    ChargeConfirmResult fail(Long memberId, String chargeNo, String failureCode, String failureMessage);

    /**
     * 본인 충전 건의 현재 상태를 조회한다. 승인 응답이 처리 중이었을 때 프론트가 최종 결과를 다시 확인하는 데 쓴다.
     * 다른 회원의 충전 건은 없는 것과 같게 404로 응답해, 충전 번호의 존재 여부를 드러내지 않는다.
     */
    ChargeConfirmResult getStatus(Long memberId, String chargeNo);
}
