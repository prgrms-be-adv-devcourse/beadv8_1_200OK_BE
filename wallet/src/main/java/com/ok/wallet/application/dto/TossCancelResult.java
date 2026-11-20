package com.ok.wallet.application.dto;

/**
 * 토스 결제 취소 호출 결과. 호출하는 쪽은 취소됐다고 확인된 경우에만 충전 건에 취소를 기록한다.
 * 취소는 돈을 돌려주는 일이라, 결과가 애매하면 Unknown으로 두고 호출하는 쪽이 결제 조회로 실제 상태를 확인한다.
 */
public sealed interface TossCancelResult {

    /** 이번 요청으로 전액 취소됐다. */
    record Canceled() implements TossCancelResult {
    }

    /**
     * 토스가 이미 취소된 결제라고 답했다({@code ALREADY_CANCELED_PAYMENT}).
     * 다른 경로(토스 상점관리자 등)로 취소됐을 수 있어, 호출하는 쪽이 결제 조회로 전액 취소인지 확인한다.
     */
    record AlreadyCanceled() implements TossCancelResult {
    }

    /** 토스가 취소할 수 없다고 분명히 답했다. 취소되지 않았다. */
    record Rejected(String code, String message) implements TossCancelResult {
    }

    /** 취소됐는지 알 수 없다. 응답만으로 충전 건을 바꾸지 않고 결제 조회로 확인한다. */
    record Unknown(String reason) implements TossCancelResult {
    }
}
