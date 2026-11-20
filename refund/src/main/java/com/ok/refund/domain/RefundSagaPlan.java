package com.ok.refund.domain;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 승인 뒤 단계에서 다음에 할 일을 정하는 정책이다(#616). 단계 순서(주문 → 지갑(0원이면 건너뜀) → 재고 → 완료)를 아는 곳은 여기 하나다.
 * 재고가 다시 해도 같은 이유로 거절되면 앞으로 가지 않고 지갑 돈을 다시 가져온다(회수, #546).
 * 지갑이 반환에 실패했거나 회수가 성공하면 주문 취소 기록을 되돌리고, 주문이 되돌리면 환불을 실패로 끝낸다(#622).
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
        PRODUCT_RELEASED,
        /** 상품이 재고 해제를 다시 해도 같은 이유로 거절해 재고 줄을 멈췄다(PRODUCT_RELEASE_REFUSED, #546) */
        PRODUCT_REFUSED,
        /** 지갑 돈 다시 가져오기(회수) 요청을 발행했다(#546) */
        WALLET_REVERT_PUBLISHED,
        /** 지갑이 반환 실패를 알려 지갑 줄을 멈췄다(거르기를 통과함, #622). 결과 불일치·이미 멈춘 줄은 이 답이 아니다 */
        WALLET_FAILED,
        /** 지갑이 돌려준 돈을 다시 가져왔다(회수 성공, 거르기를 통과함, #622) */
        WALLET_REVERTED,
        /** 주문에 취소 기록 되돌리기 요청을 발행했다(#622) */
        ORDER_REVERT_PUBLISHED,
        /** 주문이 취소 기록을 되돌렸다(거르기를 통과함, #622) */
        ORDER_REVERTED
    }

    /** 다음에 할 일 */
    public enum Action {
        CALL_ORDER,
        CALL_WALLET,
        CALL_PRODUCT,
        /** 지갑에 돌려준 돈을 다시 가져와 달라고 보낸다(회수, #546) */
        CALL_WALLET_REVERT,
        /** 주문에 취소 기록을 되돌려 달라고 보낸다(#622) */
        CALL_ORDER_REVERT,
        COMPLETE,
        /** 주문이 취소 기록을 되돌렸으니 환불을 실패로 끝낸다(#622) */
        FAIL,
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
     * 재고가 다시 해도 같은 이유로 거절되면 돌려준 돈이 있을 때만 회수한다(#546). 0원은 돌려준 돈이 없어 가져올 것도 없으니 멈춘다
     * (사람이 본다). 0원에서 주문만 되돌리지 않는 이유: 상품 409에는 "주문 전체 해제로 재고가 이미 돌아옴"도 섞여 있어,
     * 그때 주문을 되돌리면 상품은 보내야 하는데 재고는 풀린 채라 한 번 더 팔릴 수 있다. 돈이 움직이지 않은 0원은 멈춰도 손해가 없다(#622 결정 D1).
     * 5번을 다 써서 멈춘 재고(일시 장애)는 이 답이 아니라 회수하지 않는다(11/16 결정).
     * 되돌리기 순서는 지갑 먼저, 주문 다음이다(PR #537). 지갑이 반환에 실패했으면 돌려준 돈이 없어 바로 주문을 되돌리고,
     * 회수가 성공하면 이어서 주문을 되돌린다. 회수가 실패하면(구매자가 이미 씀) 주문을 되돌리지 않는다. 돈은 구매자에게 있는데
     * 주문만 되돌리면 판매자가 상품을 보내야 하기 때문이다(그 답은 이 계획에 오지 않는다, 지휘자가 회수 줄을 멈추기만 함).
     * 한계: 돈이 있는 환불은 0원과 달리 회수 뒤 주문까지 되돌린다. 409가 "이미 통째로 풀림"이었다면 재고가 한 번 더 팔릴 수 있지만,
     * 정상 흐름에서는 생기지 않는 경우라 PR #537 약속대로 두고, 상품이 그 경우를 다른 코드로 답하게 하는 일은 #709에서 정한다.
     */
    public static Action afterEvent(Refund refund, Event event) {
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return Action.STOP;
        }
        return switch (event) {
            case ORDER_REFLECTED -> refund.getRefundAmount() > 0 ? Action.CALL_WALLET : Action.CALL_PRODUCT;
            case ORDER_REFUSED -> Action.REJECT;
            case WALLET_PUBLISHED, WALLET_REVERT_PUBLISHED, ORDER_REVERT_PUBLISHED -> Action.WAIT;
            case WALLET_COMPLETED -> Action.CALL_PRODUCT;
            case PRODUCT_RELEASED -> Action.COMPLETE;
            case PRODUCT_REFUSED -> refund.getRefundAmount() > 0 ? Action.CALL_WALLET_REVERT : Action.STOP;
            case WALLET_FAILED, WALLET_REVERTED -> Action.CALL_ORDER_REVERT;
            case ORDER_REVERTED -> Action.FAIL;
        };
    }

    /**
     * 방금 받은 답 없이(알람이 깨운 경우) 단계 줄만 보고 다음 할 일을 정한다(#616 환불 번호 입구, #576).
     * 차례대로 앞 단계가 성공했는지 보며 내려간다. 다음 단계 줄은 앞 단계 성공과 같은 구간에서 만들므로(주문 성공 → 지갑·재고 줄,
     * 지갑 완료 → 재고 줄), 앞 단계가 성공했는데 다음 줄이 없거나 주문 줄 자체가 없으면 있을 수 없는 상태라 멈춘다(#616 결정 13).
     * 재고 줄이 있으면 지갑 완료를 이미 받은 것이라 지갑 줄 상태는 보지 않는다. 앞선 불일치로 지갑 줄이 멈춰 있어도
     * 맞는 완료를 받아 재고로 간 환불(#544 결정 8)은 재고 줄 상태로 이어 간다. 지갑 답 거르기(WalletResultPolicy)도 같은 기준을 쓴다.
     * 시도 중은 만료 시각이 지났으면 그 단계를 다시 부르고(#576), 아니면 기다림이다. 결과 대기도 기다리는 시각이 지났으면
     * 같은 요청을 다시 보내려고 그 단계를 부르고(#577), 아니면 기다림이다.
     * 회수 줄이 있으면 다른 줄보다 먼저 본다(#546). 회수를 시작한 환불은 앞으로 가지 않으므로 지갑 반환을 다시 요청하지 않는다
     * (알람의 다시 진행, 결과 대기 시간 초과의 다시 요청 모두). 회수가 끝나면(다시 가져옴·실패) 멈춘다. 회수 성공 뒤의 주문 되돌리기는
     * 회수 결과를 기록한 구간이 되돌리기 줄을 함께 만들므로, 그 줄이 있으면 다음 규칙대로 회수 줄보다 앞서 본다.
     * 주문 되돌리기 줄이 있으면 회수 줄을 포함해 가장 먼저 본다(#622). 되돌리기 줄이 생긴 환불은 앞 단계(지갑 실패로 멈춘 지갑 줄, 회수가 끝난 회수 줄)가
     * 모두 끝난 뒤라 그 줄들을 보면 멈춤이 되어, 되돌리기를 다시 보내지 못하고 남은 줄이 막힘으로 바뀐다.
     * 되돌리기 줄이 끝났으면 멈춘다. 성공했으면 같은 구간에서 환불도 실패로 끝내 처리 중으로 남지 않으므로, 여기 오는 것은 되돌리기가 실패한 경우다
     */
    public static Action fromSteps(Refund refund, List<RefundStep> steps, LocalDateTime now) {
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return Action.STOP;
        }
        Map<RefundStepType, RefundStep> byType = new EnumMap<>(RefundStepType.class);
        for (RefundStep step : steps) {
            byType.put(step.getType(), step);
        }
        RefundStep orderRevert = byType.get(RefundStepType.ORDER_REVERT);
        if (orderRevert != null) {
            return orderRevert.getStatus().isFinished() ? Action.STOP : actionFor(orderRevert, Action.CALL_ORDER_REVERT, now);
        }
        RefundStep revert = byType.get(RefundStepType.WALLET_REVERT);
        if (revert != null) {
            return revert.getStatus().isFinished() ? Action.STOP : actionFor(revert, Action.CALL_WALLET_REVERT, now);
        }
        RefundStep order = byType.get(RefundStepType.ORDER);
        if (order == null) {
            return Action.STOP;
        }
        if (order.getStatus() != RefundStepStatus.SUCCEEDED) {
            return actionFor(order, Action.CALL_ORDER, now);
        }
        RefundStep product = byType.get(RefundStepType.PRODUCT);
        if (refund.getRefundAmount() > 0 && product == null) {
            RefundStep wallet = byType.get(RefundStepType.WALLET);
            if (wallet == null) {
                return Action.STOP;
            }
            if (wallet.getStatus() != RefundStepStatus.SUCCEEDED) {
                return actionFor(wallet, Action.CALL_WALLET, now);
            }
        }
        if (product == null) {
            return Action.STOP;
        }
        if (product.getStatus() != RefundStepStatus.SUCCEEDED) {
            return actionFor(product, Action.CALL_PRODUCT, now);
        }
        return Action.COMPLETE;
    }

    // 성공하지 않은 단계 줄의 상태로 할 일을 정한다. 할 차례면 그 단계를 부르고, 시도 중·결과 대기면 기다리고,
    // 거절됨·멈춤이면 멈춘다(거절된 주문은 승인 흐름이 이미 거절로 끝냈어야 해서 처리 중으로 남았다면 사람이 본다).
    // 시도 중이라도 만료 시각이 지났으면 시도하던 쪽이 멈춘 것이라 다시 부른다(#576). 결과 대기도 기다리는 시각이 지났으면
    // 결과가 늦거나 버려진 것이라 같은 요청을 다시 보낸다(#577). 실제로 다시 집을지는 지휘자가 잠근 뒤 다시 본다
    private static Action actionFor(RefundStep step, Action call, LocalDateTime now) {
        return switch (step.getStatus()) {
            case READY -> call;
            case IN_PROGRESS -> step.isExpired(now) ? call : Action.WAIT;
            case AWAITING_RESULT -> step.isResultOverdue(now) ? call : Action.WAIT;
            case REFUSED, STOPPED -> Action.STOP;
            case SUCCEEDED -> throw new IllegalArgumentException("성공한 단계 줄은 넘기지 않는다. step=" + step.getType());
        };
    }
}
