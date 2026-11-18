package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.ChargeAdminDetailResult;
import com.ok.wallet.application.dto.ChargeAdminResult;

import java.util.List;

/**
 * 복구가 끝내지 못한 충전 건을 관리자가 처리하는 유스케이스.
 * 무엇을 할지는 관리자가 토스 결제 상태를 보고 고르고, 처리 직전에 토스에 다시 물어 상태와 맞지 않는 선택은 막는다.
 */
public interface ChargeAdminUseCase {

    /** 관리자가 처리할 충전 건(복구가 멈춘 건, 승인 뒤 실패한 건)을 읽는다. */
    List<ChargeAdminResult> findTargets();

    /**
     * 충전 건과 토스 결제 상태를 함께 읽는다.
     *
     * @throws com.ok.common.exception.RestApiException 충전 건이 없으면 {@code CHARGE_NOT_FOUND}
     */
    ChargeAdminDetailResult findDetail(String chargeNo);

    /**
     * 토스가 승인한 멈춘 건을 잔액에 반영하고 완료한다.
     *
     * @throws com.ok.common.exception.RestApiException 멈춘 처리 중 건이 아니면 {@code CHARGE_NOT_ADMIN_HANDLEABLE},
     *                                                  토스가 같은 금액으로 승인하지 않았으면 {@code CHARGE_TOSS_STATUS_MISMATCH},
     *                                                  토스 상태를 모르면 {@code CHARGE_TOSS_STATUS_UNKNOWN}
     */
    ChargeAdminResult complete(Long adminId, String chargeNo);

    /**
     * 토스가 승인하지 않은 멈춘 건을 실패로 바꾼다.
     *
     * @param reason 실패 사유 (충전 건에 남는다)
     * @throws com.ok.common.exception.RestApiException 멈춘 처리 중 건이 아니면 {@code CHARGE_NOT_ADMIN_HANDLEABLE},
     *                                                  토스 결제가 승인됐거나 아직 진행 중이면 {@code CHARGE_TOSS_STATUS_MISMATCH},
     *                                                  토스 상태를 모르면 {@code CHARGE_TOSS_STATUS_UNKNOWN}
     */
    ChargeAdminResult fail(Long adminId, String chargeNo, String reason);

    /**
     * 토스 결제를 전액 취소(환불)하고 충전 건에 기록한다. 처리 중인 건은 실패가 된다.
     *
     * @param reason 취소 사유 (토스에 보내고 충전 건에 남는다, 200자 이하)
     * @throws com.ok.common.exception.RestApiException 멈춘 건이나 승인 뒤 실패한 건이 아니면 {@code CHARGE_NOT_ADMIN_HANDLEABLE},
     *                                                  토스 결제가 전액 승인 상태가 아니면 {@code CHARGE_TOSS_STATUS_MISMATCH},
     *                                                  토스가 거절하면 {@code CHARGE_PAYMENT_CANCEL_REJECTED},
     *                                                  취소 결과를 모르면 {@code CHARGE_TOSS_STATUS_UNKNOWN}
     */
    ChargeAdminResult cancelPayment(Long adminId, String chargeNo, String reason);
}
