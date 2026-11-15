package com.ok.refund.domain;

import java.util.Objects;

/**
 * 지갑이 보낸 예치금 반환 결과(완료·실패)를 받을지 거르는 정책이다(#616). 받음·불일치·넘김만 답하고 다음 단계는 정하지 않는다.
 * 다음 단계는 {@link RefundSagaPlan} 한 곳에서 정한다. 여기서 다음 단계까지 정하면 순서 판단이 두 곳으로 갈라진다(#575 결정 5).
 * 지휘자는 잠그기 전에 먼저 읽은 값으로 한 번, 잠근 뒤 다시 읽은 값으로 한 번 부른다. 읽은 뒤 잠그기 전에 상태가 바뀔 수 있어서다.
 * 지갑 반환 답 전용이다. 되돌리기(돈 다시 가져오기) 답은 결과의 구매자·금액이 지갑 기록 값이라 비교 기준이 달라 따로 거른다(#546·#572).
 */
public final class WalletResultPolicy {

    /** 지갑이 보낸 결과의 종류 */
    public enum Kind {
        COMPLETED,
        FAILED
    }

    /** 거른 결과 */
    public enum Decision {
        /** 받는다. 완료면 재고 단계로, 실패면 지갑 줄을 멈춤으로 남긴다 */
        ACCEPT,
        /** 금액·주문·구매자가 환불과 달라 불일치로 남긴다 */
        MISMATCH,
        /** 아무것도 쓰지 않고 넘긴다. 이유는 {@link SkipReason} */
        SKIP
    }

    /** 넘기는 이유. 이유마다 지휘자가 남기는 로그 수준이 다르다 */
    public enum SkipReason {
        /** 환불이 없다 */
        REFUND_NOT_FOUND,
        /** 이미 완료된 환불이다. 완료 메시지면 같은 메시지가 두 번 온 정상, 실패 메시지면 앞선 완료와 모순이다 */
        ALREADY_COMPLETED,
        /** 처리 중이 아니다(요청됨·거절·철회). 지갑이 돈을 움직인 환불이 이 상태일 수 없다 */
        NOT_PROCESSING,
        /** 지갑 완료를 받아 재고 단계가 시작된 환불이다. 실패나 불일치를 남기면 앞선 완료와 모순이다 */
        AFTER_WALLET_SUCCESS,
        /** 같은 이유의 실패가 이미 기록돼 있다 */
        SAME_FAILURE
    }

    /**
     * 거른 결과와 넘기는 이유. 넘기지 않으면 이유는 {@code null}이다.
     */
    public record Verdict(Decision decision, SkipReason skipReason) {

        static Verdict accept() {
            return new Verdict(Decision.ACCEPT, null);
        }

        static Verdict mismatch() {
            return new Verdict(Decision.MISMATCH, null);
        }

        static Verdict skip(SkipReason reason) {
            return new Verdict(Decision.SKIP, reason);
        }
    }

    private WalletResultPolicy() {
    }

    /**
     * 지갑 결과를 받을지 정한다.
     * 실패 메시지는 금액 비교보다 "재고 단계 시작"을 먼저 본다. 달라서 불일치로 남기는 경우에도 재고 단계가 시작된 환불에 지갑 실패를 남기면 안 되기 때문이다.
     * 완료 메시지는 재고 줄이 있어도 받는다. 재고가 실패해 "할 차례"로 남은 환불에 같은 완료가 다시 오면(오류 처리기의 다시 시도) 재고를 다시 시도해야 해서다.
     * 단, 금액 등이 달라 불일치로 남길 때는 재고 단계가 시작됐으면 넘긴다(앞선 완료와 모순).
     * 이미 불일치로 멈춘 지갑 줄에 맞는 완료가 오면 받는다(#544 결정 8, 지갑 줄은 멈춤 그대로 두고 환불은 완료로 간다).
     *
     * @param refund      먼저 읽었거나 잠근 환불. 없으면 {@code null}
     * @param walletStep  지갑 단계 줄. 없으면 {@code null}
     * @param productStep 재고 단계 줄. 지갑 완료를 반영하기 전이면 {@code null}
     * @param reason      실패 메시지가 알려 준 이유. 완료 메시지면 {@code null}
     */
    public static Verdict judge(Kind kind, Refund refund, RefundStep walletStep, RefundStep productStep,
                                Long orderId, Long buyerId, long amount, String reason) {
        if (refund == null) {
            return Verdict.skip(SkipReason.REFUND_NOT_FOUND);
        }
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            return Verdict.skip(SkipReason.ALREADY_COMPLETED);
        }
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return Verdict.skip(SkipReason.NOT_PROCESSING);
        }
        if (kind == Kind.FAILED && productStep != null) {
            return Verdict.skip(SkipReason.AFTER_WALLET_SUCCESS);
        }
        if (!matches(refund, orderId, buyerId, amount)) {
            return productStep != null ? Verdict.skip(SkipReason.AFTER_WALLET_SUCCESS) : Verdict.mismatch();
        }
        if (kind == Kind.FAILED && walletStep != null && walletStep.isStoppedWith(reason)) {
            return Verdict.skip(SkipReason.SAME_FAILURE);
        }
        return Verdict.accept();
    }

    // 지갑 결과의 주문·구매자·금액이 모두 환불과 같은지 본다. 완료·실패 메시지가 같은 기준을 쓰게 한 곳에 둔다.
    // ID는 equals로 비교한다(Long을 ==로 비교하면 캐시 범위 밖 값에서 같아도 다르다고 나온다)
    private static boolean matches(Refund refund, Long orderId, Long buyerId, long amount) {
        return Objects.equals(refund.getOrderId(), orderId)
                && Objects.equals(refund.getBuyerId(), buyerId)
                && refund.getRefundAmount() == amount;
    }
}
