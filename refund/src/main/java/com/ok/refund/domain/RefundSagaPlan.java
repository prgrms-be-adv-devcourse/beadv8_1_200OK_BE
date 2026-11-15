package com.ok.refund.domain;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 승인 뒤 단계에서 다음에 할 일을 정하는 정책이다(#616). 단계 순서(주문 → 지갑(0원이면 건너뜀) → 재고 → 완료)를 아는 곳은 여기 하나다.
 * 실행(잠그기, 단계 줄 만들기·고치기, 다른 모듈 부르기)은 지휘자가 한다. 이 객체는 DB·Spring을 모른다.
 * 입력은 환불·단계 줄·방금 받은 답·지금 시각이다(#575 결정 12). 단계 줄만 보면 "불일치로 멈춘 지갑 줄 + 맞는 완료 메시지"처럼
 * 방금 받은 답이 있어야 정할 수 있는 경우를 놓친다(#544 결정 8). 그래서 방금 받은 답이 있으면 그것으로 정하고({@link #afterEvent}),
 * 없으면(알람이 깨운 경우) 단계 줄로 정한다({@link #fromSteps}).
 */
public final class RefundSagaPlan {

    /** 방금 받은 답(지휘자를 깨운 일) */
    public enum Event {
        /** 주문이 취소를 반영했다 */
        ORDER_REFLECTED,
        /** 주문이 반영을 거절했다(이미 출고 등) */
        ORDER_REFUSED,
        /** 지갑 반환 요청을 발행했다 */
        WALLET_PUBLISHED,
        /** 지갑 완료를 받았다(거르기를 통과함) */
        WALLET_COMPLETED,
        /** 재고를 되돌렸다 */
        PRODUCT_RELEASED
    }

    /** 다음에 할 일 */
    public enum Action {
        CALL_ORDER,
        CALL_WALLET,
        CALL_PRODUCT,
        COMPLETE,
        REJECT,
        /** 누군가 진행 중이거나 지갑 결과를 기다린다. 아무것도 하지 않는다 */
        WAIT,
        /** 자동으로 더 진행하지 않는다. 사람이 본다(있을 수 없는 상태, 멈춘 단계, 처리 중이 아닌 환불) */
        STOP
    }

    private RefundSagaPlan() {
    }

    /**
     * 방금 받은 답으로 다음 할 일을 정한다. 처리 중이 아닌 환불은 더 할 일이 없어 멈춤이다.
     * 지갑 완료를 받으면 지갑 줄이 앞선 불일치로 멈춰 있어도 재고로 간다(#544 결정 8, 지갑 줄은 멈춤 그대로).
     */
    public static Action afterEvent(Refund refund, Event event) {
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return Action.STOP;
        }
        return switch (event) {
            case ORDER_REFLECTED -> refund.getRefundAmount() > 0 ? Action.CALL_WALLET : Action.CALL_PRODUCT;
            case ORDER_REFUSED -> Action.REJECT;
            case WALLET_PUBLISHED -> Action.WAIT;
            case WALLET_COMPLETED -> Action.CALL_PRODUCT;
            case PRODUCT_RELEASED -> Action.COMPLETE;
        };
    }

    /**
     * 방금 받은 답 없이(알람이 깨운 경우) 단계 줄만 보고 다음 할 일을 정한다(#616 환불 번호 입구, #576).
     * 차례대로 앞 단계가 성공했는지 보며 내려간다. 다음 단계 줄은 앞 단계 성공과 같은 구간에서 만들므로(주문 성공 → 지갑·재고 줄,
     * 지갑 완료 → 재고 줄), 앞 단계가 성공했는데 다음 줄이 없거나 주문 줄 자체가 없으면 있을 수 없는 상태라 멈춘다(#616 결정 13).
     * 재고 줄이 있으면 지갑 완료를 이미 받은 것이라 지갑 줄 상태는 보지 않는다. 앞선 불일치로 지갑 줄이 멈춰 있어도
     * 맞는 완료를 받아 재고로 간 환불(#544 결정 8)은 재고 줄 상태로 이어 간다. 지갑 답 거르기(WalletResultPolicy)도 같은 기준을 쓴다.
     * 시도 중·결과 대기는 기다림이다. 시도 중이 오래 멈춘 줄을 다시 시작하는 일(#576)과 결과 대기 시간 초과(#577)는
     * 지금 시각({@code now})으로 그 이슈에서 더한다.
     */
    public static Action fromSteps(Refund refund, List<RefundStep> steps, LocalDateTime now) {
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return Action.STOP;
        }
        Map<RefundStepType, RefundStep> byType = new EnumMap<>(RefundStepType.class);
        for (RefundStep step : steps) {
            byType.put(step.getType(), step);
        }
        RefundStep order = byType.get(RefundStepType.ORDER);
        if (order == null) {
            return Action.STOP;
        }
        if (order.getStatus() != RefundStepStatus.SUCCEEDED) {
            return actionFor(order, Action.CALL_ORDER);
        }
        RefundStep product = byType.get(RefundStepType.PRODUCT);
        if (refund.getRefundAmount() > 0 && product == null) {
            RefundStep wallet = byType.get(RefundStepType.WALLET);
            if (wallet == null) {
                return Action.STOP;
            }
            if (wallet.getStatus() != RefundStepStatus.SUCCEEDED) {
                return actionFor(wallet, Action.CALL_WALLET);
            }
        }
        if (product == null) {
            return Action.STOP;
        }
        if (product.getStatus() != RefundStepStatus.SUCCEEDED) {
            return actionFor(product, Action.CALL_PRODUCT);
        }
        return Action.COMPLETE;
    }

    // 성공하지 않은 단계 줄의 상태로 할 일을 정한다. 할 차례면 그 단계를 부르고, 시도 중·결과 대기면 기다리고,
    // 거절됨·멈춤이면 멈춘다(거절된 주문은 승인 흐름이 이미 거절로 끝냈어야 해서 처리 중으로 남았다면 사람이 본다)
    private static Action actionFor(RefundStep step, Action call) {
        return switch (step.getStatus()) {
            case READY -> call;
            case IN_PROGRESS, AWAITING_RESULT -> Action.WAIT;
            case REFUSED, STOPPED -> Action.STOP;
            case SUCCEEDED -> throw new IllegalArgumentException("성공한 단계 줄은 넘기지 않는다. step=" + step.getType());
        };
    }
}
