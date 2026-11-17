package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.OrderMessageFailureCommand;

/**
 * 주문 리스너가 끝내 처리하지 못한 Kafka 메시지를 실패 기록 표에 남긴다 (#666). 주문 전용 Kafka 오류 처리기가 부른다.
 */
public interface OrderMessageFailureUseCase {

    /**
     * 실패를 기록한다. 같은 그룹·토픽·파티션·오프셋이 이미 기록돼 있으면 다시 남기지 않는다.
     * 기록하지 못하면 예외를 그대로 던진다. 오류 처리기는 이 메시지를 다시 받아 DB가 살아난 뒤 다시 처리하거나 기록한다.
     */
    void record(OrderMessageFailureCommand command);
}
