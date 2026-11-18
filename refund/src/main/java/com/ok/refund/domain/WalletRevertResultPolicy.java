package com.ok.refund.domain;

import java.util.Objects;

/**
 * 지갑이 보낸 돈 다시 가져오기(회수) 결과(다시 가져옴·실패)를 받을지 거르는 정책이다(#546). 받음·불일치·넘김만 답한다.
 * 주문·구매자·금액이 기대한 값과 다르면 불일치로 회수 줄을 멈춘다.
 * 회수 결과의 구매자·금액은 지갑이 실제로 돌려준 기록 값이다(PR #537 약속). 재고 거절로 시작한 회수는 지갑 완료를 환불 값과 같을 때만
 * 받았으므로 환불 값과 비교하고, 지갑이 다른 값으로 돌려줬다고 알려 시작한 회수(#572)는 회수 줄에 남긴 그 값과 비교한다.
 * 지갑 반환 결과와 따로 둔 이유: 보는 단계 줄(회수 줄)과 넘기는 경우(이미 끝난 회수 줄)가 달라, 한 정책에 넣으면 경우마다 갈라진다.
 * 지휘자는 환불 행을 잠근 뒤 한 번만 부른다. 회수 결과는 다른 모듈을 부르지 않아 잠그기 전에 먼저 읽을 까닭이 없다.
 */
public final class WalletRevertResultPolicy {

    /** 지갑이 보낸 회수 결과의 종류 */
    public enum Kind {
        REVERTED,
        FAILED
    }

    /** 거른 결과 */
    public enum Decision {
        /** 받는다. 다시 가져옴이면 회수 줄을 성공, 실패면 멈춤으로 남긴다 */
        ACCEPT,
        /** 주문·구매자·금액이 기대한 값(환불 값, 또는 #572 회수면 지갑이 처음 알린 값)과 달라 회수 줄을 불일치로 멈춘다 */
        MISMATCH,
        /** 아무것도 쓰지 않고 넘긴다. 이유는 {@link SkipReason} */
        SKIP
    }

    /** 넘기는 이유. 이유마다 지휘자가 남기는 로그 수준이 다르다 */
    public enum SkipReason {
        /** 환불이 없다 */
        REFUND_NOT_FOUND,
        /** 처리 중이 아니다. 회수는 처리 중 환불에서만 시작한다 */
        NOT_PROCESSING,
        /** 회수 줄이 없다. 회수 요청을 보낸 적 없는 환불이라 있을 수 없는 상태다 */
        NO_REVERT_STEP,
        /** 회수 줄이 이미 끝났다(성공·멈춤). 지갑은 같은 refundId에 첫 결과를 다시 보내므로 같은 결과면 정상 중복이다 */
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

    private WalletRevertResultPolicy() {
    }

    /**
     * 회수 결과를 받을지 정한다. 끝난 회수 줄은 금액 비교보다 먼저 넘긴다. 끝 상태는 바뀌지 않아 남길 곳이 없기 때문이다.
     *
     * @param refund     잠근 환불. 없으면 {@code null}
     * @param revertStep 회수 줄. 없으면 {@code null}
     */
    public static Verdict judge(Refund refund, RefundStep revertStep, Long orderId, Long buyerId, long amount) {
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
        // 지갑이 환불과 다른 구매자·금액으로 돌려줬다고 알려 시작한 회수(#572)는 그때 알린 값과 비교한다. 지갑은 자기 반환 기록으로 회수하고
        // 결과에도 그 값을 넣기 때문이다(지갑 #611). 그 밖의 회수(재고 거절)는 완료를 환불 값과 같을 때만 받았으므로 환불 값과 비교한다.
        // 주문 번호는 어느 경우든 환불 값이다. 주문 번호까지 다른 완료는 회수하지 않는다(#572 결정 1)
        // ID는 equals로 비교한다(Long을 ==로 비교하면 캐시 범위 밖 값에서 같아도 다르다고 나온다)
        boolean matches = Objects.equals(refund.getOrderId(), orderId)
                && Objects.equals(revertStep.expectedRevertBuyerId(refund), buyerId)
                && revertStep.expectedRevertAmount(refund) == amount;
        return matches ? Verdict.accept() : Verdict.mismatch();
    }
}
