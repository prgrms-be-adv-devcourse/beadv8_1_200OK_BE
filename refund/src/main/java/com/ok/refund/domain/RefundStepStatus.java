package com.ok.refund.domain;

/** 승인 뒤 한 단계의 진행 상태. */
public enum RefundStepStatus {

    /** 다시 시도할 차례. 실패 뒤 다음 시도를 기다린다 */
    READY,
    /** 지금 시도 중 */
    IN_PROGRESS,
    /** 요청은 보냈고 결과 메시지를 기다린다 */
    AWAITING_RESULT,
    /** 성공 */
    SUCCEEDED,
    /** 상대 모듈이 거절 */
    REFUSED,
    /** 자동으로 더 하지 않고 멈춤. 운영자가 본다 */
    STOPPED;

    /** 끝 상태(성공·거절·멈춤)인지. 끝 상태는 다른 상태로 바뀌지 않는다 */
    public boolean isFinished() {
        return this == SUCCEEDED || this == REFUSED || this == STOPPED;
    }
}
