package com.ok.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;

/**
 * JSON 한 줄이 이벤트 상한(기본 8KB)을 넘으면 본문을 줄인 이벤트로 바꿔 인코딩하는 {@link LogstashEncoder}.
 *
 * <p>필드 하나는 이미 1KB로 잘리지만 인자가 많은 이벤트는 합쳐서 커질 수 있다. 다 자른 JSON은 깨지므로 바이트를 자르지 않고,
 * 같은 MDC를 가진 짧은 안내 이벤트로 대신 낸다. 이렇게 하면 traceId 등 흐름을 묶는 키는 남고 마스킹도 같은 경로를 탄다.
 */
public class BoundedLogstashEncoder extends LogstashEncoder {

    private int maxEventBytes = 8192;

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encoded = super.encode(event);
        if (encoded.length <= maxEventBytes) {
            return encoded;
        }
        Logger logger = ((LoggerContext) getContext()).getLogger(event.getLoggerName());
        LoggingEvent replacement = new LoggingEvent(Logger.FQCN, logger, event.getLevel(),
                "이벤트가 상한({}바이트)을 넘어 본문을 생략했다. 원래 크기={}바이트", null,
                new Object[] {maxEventBytes, encoded.length});
        replacement.setInstant(event.getInstant());
        replacement.setMDCPropertyMap(event.getMDCPropertyMap());
        return super.encode(replacement);
    }

    public void setMaxEventBytes(int maxEventBytes) {
        this.maxEventBytes = maxEventBytes;
    }
}
