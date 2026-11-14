package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.MessageFailureCommand;

/**
 * 지갑이 받은 메시지 중 끝내 처리하지 못한 메시지를 기록하는 유스케이스.
 */
public interface MessageFailureUseCase {

    /**
     * 처리하지 못한 메시지를 원문과 함께 대기 상태로 기록한다.
     * 같은 메시지(토픽·파티션·오프셋)는 한 번만 남는다.
     * 기록하지 못하면 예외를 그대로 던진다. 부르는 쪽은 이 메시지를 건너뛰지 않고 다시 받아야 한다.
     */
    void record(MessageFailureCommand command);
}
