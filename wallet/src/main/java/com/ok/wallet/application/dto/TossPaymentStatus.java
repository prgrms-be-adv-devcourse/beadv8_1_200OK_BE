package com.ok.wallet.application.dto;

/**
 * 토스 결제 조회로 받은 결제 상태. 이름과 뜻은 토스페이먼츠 결제 객체 문서를 따른다.
 * 토스가 이 목록에 없는 상태를 보내면 조회 결과를 알 수 없음으로 두어, 모르는 상태로 충전 건을 바꾸지 않는다.
 */
public enum TossPaymentStatus {

    /** 결제를 만든 뒤 인증 전 */
    READY,

    /** 인증을 마쳤고 승인 전. 승인 API를 부르면 완료된다 */
    IN_PROGRESS,

    /** 가상계좌 입금 대기. 충전은 가상계좌를 쓰지 않아 나오면 안 되는 상태다 */
    WAITING_FOR_DEPOSIT,

    /** 승인됨 */
    DONE,

    /** 승인 뒤 취소됨 */
    CANCELED,

    /** 승인 뒤 일부 취소됨 */
    PARTIAL_CANCELED,

    /** 승인 실패 */
    ABORTED,

    /** 승인 가능 시간이 지나 취소됨 */
    EXPIRED
}
