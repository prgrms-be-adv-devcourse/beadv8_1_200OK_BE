package com.ok.common.logging;

import java.util.concurrent.ThreadLocalRandom;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * HTTP 요청 성공 로그({@code eventType=HTTP_IN})를 설정한 비율만 남긴다.
 *
 * <p>요청마다 남는 성공 로그가 로그량의 대부분이라 전부 남기면 디스크가 먼저 찬다. 다른 eventType은 건드리지 않는다.
 * 이 필터는 성공 appender에만 붙인다. 실패(WARN 이상)는 실패 appender로 가서 이 필터를 거치지 않으므로 줄어들지 않는다.
 */
public class HttpSuccessSamplingFilter extends Filter<ILoggingEvent> {

    static final String HTTP_IN = "HTTP_IN";
    private static final String EVENT_TYPE_KEY = "eventType";

    private double rate = 0.1;

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!HTTP_IN.equals(event.getMDCPropertyMap().get(EVENT_TYPE_KEY))) {
            return FilterReply.NEUTRAL;
        }
        if (ThreadLocalRandom.current().nextDouble() < rate) {
            return FilterReply.NEUTRAL;
        }
        LogDropCounter.increment(LogDropCounter.Reason.SAMPLED, 1);
        return FilterReply.DENY;
    }

    /** 남길 비율(0~1). 0.1이면 10%를 남긴다. */
    public void setRate(double rate) {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException("샘플링 비율은 0 이상 1 이하여야 합니다. 입력값: " + rate);
        }
        this.rate = rate;
    }
}
