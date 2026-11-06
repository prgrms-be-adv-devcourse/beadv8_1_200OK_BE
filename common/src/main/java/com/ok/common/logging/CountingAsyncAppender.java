package com.ok.common.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 큐가 가득 차 버려지는 이벤트를 세는 {@link AsyncAppender}.
 *
 * <p>logback의 {@code neverBlock}은 큐가 차면 이벤트를 조용히 버려서 몇 건이 사라졌는지 알 수 없다.
 * 큐 여유를 먼저 확인해 가득 찼으면 직접 버리고 센다. 확인과 삽입 사이에 다른 스레드가 큐를 채울 수 있어 건수는 실제보다 몇 건 적을 수 있다.
 * 로그 때문에 요청 스레드가 락을 잡지 않게 하려는 선택이다.
 */
public class CountingAsyncAppender extends AsyncAppender {

    @Override
    protected void append(ILoggingEvent event) {
        if (isNeverBlock() && getRemainingCapacity() == 0) {
            LogDropCounter.increment(LogDropCounter.Reason.QUEUE_FULL, 1);
            return;
        }
        super.append(event);
    }
}
