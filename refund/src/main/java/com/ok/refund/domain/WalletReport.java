package com.ok.refund.domain;

/**
 * 지갑이 보낸 반환 결과가 환불과 달라(결과 불일치) 지갑 줄을 처음 멈출 때 함께 남기는, 지갑이 보낸 값이다(#761).
 * 돈을 자동으로 되돌리지 않고 관리자가 이 값과 환불 값을 나란히 보고 지갑과 맞춘다.
 * 저장하는 값은 "지갑이 이 환불 번호에 대해 말한 값"이다. 누구의 돈인지 짝을 맞춘 결과가 아니다(메시지에 판매자·상품 칸이 없다).
 * 받는 곳(리스너)이 빈 번호, 0원 이하, 빈·너무 긴 실패 이유를 먼저 거르므로 정상 흐름에서는 아래 검사에 걸리지 않는다.
 * 그래도 리스너를 거치지 않는 호출(테스트 등)에서 반쪽짜리 기록이 남지 않게 만들 때 막는다(DB CHECK와 같은 규칙).
 *
 * @param result  지갑이 보낸 결과 종류(완료·실패)
 * @param orderId 지갑이 보낸 주문 ID
 * @param buyerId 지갑이 보낸 구매자 ID
 * @param amount  지갑이 보낸 금액(원). 완료면 돌려준 금액, 실패면 돌려주려던 금액
 * @param reason  지갑이 보낸 실패 이유. 실패일 때만 있고 완료면 {@code null}
 */
public record WalletReport(Result result, Long orderId, Long buyerId, long amount, String reason) {

    /**
     * 지갑 결과 종류. 이름이 그대로 DB 값(refund_step.wallet_result)이 되므로 바꾸면 저장된 행을 읽지 못한다.
     * 지갑 결과 거르기(WalletResultPolicy.Kind)와 따로 둔 이유: 정책의 이름을 바꿀 때 저장 값까지 함께 바뀌지 않게 하려는 것이다
     */
    public enum Result {
        COMPLETED,
        FAILED
    }

    public WalletReport {
        if (result == null || orderId == null || buyerId == null) {
            throw new IllegalArgumentException("지갑 결과 종류·주문·구매자는 비어 있을 수 없습니다. result=" + result
                    + ", orderId=" + orderId + ", buyerId=" + buyerId);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("지갑이 보낸 금액은 0보다 커야 합니다. amount=" + amount);
        }
        if (result == Result.COMPLETED && reason != null) {
            throw new IllegalArgumentException("완료 결과에는 실패 이유가 없어야 합니다. reason=" + reason);
        }
        if (result == Result.FAILED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("실패 결과에는 실패 이유가 있어야 합니다.");
        }
        // 실패 이유를 조용히 자르지 않는다. 리스너도 긴 이유를 자르지 않고 거절한다(값을 바꿔 저장하면 지갑이 보낸 값이 아니게 된다)
        if (reason != null && reason.length() > RefundStep.MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("지갑이 보낸 실패 이유가 너무 깁니다. 최대 " + RefundStep.MAX_REASON_LENGTH
                    + "자, length=" + reason.length());
        }
    }

    /** 완료 메시지의 값을 만든다. */
    public static WalletReport completed(Long orderId, Long buyerId, long amount) {
        return new WalletReport(Result.COMPLETED, orderId, buyerId, amount, null);
    }

    /** 실패 메시지의 값을 만든다. */
    public static WalletReport failed(Long orderId, Long buyerId, long amount, String reason) {
        return new WalletReport(Result.FAILED, orderId, buyerId, amount, reason);
    }
}
