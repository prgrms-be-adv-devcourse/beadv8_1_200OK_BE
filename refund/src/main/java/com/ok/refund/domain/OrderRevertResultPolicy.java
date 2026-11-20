package com.ok.refund.domain;

import java.util.Objects;

/**
 * 주문이 보낸 취소 기록 되돌리기 결과(되돌림·실패)를 받을지 거르는 정책이다(#622). 받음·불일치·넘김만 답한다.
 * 기준은 회수 결과({@link WalletRevertResultPolicy})와 같다. 주문·주문 상품이 환불과 다르면 불일치로 되돌리기 줄을 멈춘다.
 * 회수 결과와 따로 둔 이유: 비교하는 값(주문 상품)과 보는 단계 줄(되돌리기 줄)이 달라, 한 정책에 넣으면 경우마다 갈라진다.
 * 지휘자는 환불 행을 잠근 뒤 한 번만 부른다. 이 결과는 다른 모듈을 부르지 않아 잠그기 전에 먼저 읽을 까닭이 없다.
 */
public final class OrderRevertResultPolicy {

    /** 주문이 보낸 되돌리기 결과의 종류 */
    public enum Kind {
        REVERTED,
        FAILED
    }

    /** 거른 결과 */
    public enum Decision {
        /** 받는다. 되돌림이면 되돌리기 줄을 성공으로 남기고 환불을 실패로 끝낸다. 실패면 되돌리기 줄을 멈춘다 */
        ACCEPT,
        /** 주문·주문 상품이 환불과 달라 되돌리기 줄을 불일치로 멈춘다 */
        MISMATCH,
        /** 아무것도 쓰지 않고 넘긴다. 이유는 {@link SkipReason} */
        SKIP
    }

    /** 넘기는 이유. 이유마다 지휘자가 남기는 로그 수준이 다르다 */
    public enum SkipReason {
        /** 환불이 없다 */
        REFUND_NOT_FOUND,
        /** 처리 중이 아니다. 되돌림을 받아 이미 실패로 끝났거나, 되돌리기를 보낸 적 없는 끝난 환불이다 */
        NOT_PROCESSING,
        /** 되돌리기 줄이 없다. 되돌리기를 보낸 적 없는 환불이라 있을 수 없는 상태다 */
        NO_REVERT_STEP,
        /** 되돌리기 줄이 이미 끝났다(성공·멈춤). 같은 refundId 결과가 다시 오면 정상 중복이다 */
        ALREADY_FINISHED
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

    private OrderRevertResultPolicy() {
    }

    /**
     * 되돌리기 결과를 받을지 정한다. 끝난 되돌리기 줄은 값 비교보다 먼저 넘긴다. 끝 상태는 바뀌지 않아 남길 곳이 없기 때문이다.
     * 되돌림을 받아 환불이 실패로 끝난 뒤 같은 결과가 다시 오면 처리 중이 아니라서 넘긴다(정상 중복).
     *
     * @param refund      잠근 환불. 없으면 {@code null}
     * @param revertStep  되돌리기 줄. 없으면 {@code null}
     */
    public static Verdict judge(Refund refund, RefundStep revertStep, Long orderId, Long orderItemId) {
        if (refund == null) {
            return Verdict.skip(SkipReason.REFUND_NOT_FOUND);
        }
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return Verdict.skip(SkipReason.NOT_PROCESSING);
        }
        if (revertStep == null) {
            return Verdict.skip(SkipReason.NO_REVERT_STEP);
        }
        if (revertStep.getStatus().isFinished()) {
            return Verdict.skip(SkipReason.ALREADY_FINISHED);
        }
        // ID는 equals로 비교한다(Long을 ==로 비교하면 캐시 범위 밖 값에서 같아도 다르다고 나온다)
        boolean matches = Objects.equals(refund.getOrderId(), orderId)
                && Objects.equals(refund.getOrderItemId(), orderItemId);
        return matches ? Verdict.accept() : Verdict.mismatch();
    }
}
