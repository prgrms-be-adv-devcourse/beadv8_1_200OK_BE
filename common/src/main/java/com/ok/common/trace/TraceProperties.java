package com.ok.common.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추적 설정값. application.yaml의 {@code trace.*}가 바인딩된다.
 *
 * <p>값이 잘못되면 기동 시점에 바로 실패하도록 생성자에서 검증한다.
 * {@code @DefaultValue}를 두어 설정이 없는 테스트나 모듈 단독 실행에서도 기동이 막히지 않게 한다.
 *
 * @param nodeId traceId(Snowflake)에 들어가는 노드 번호(0~1023). 서버(인스턴스)마다 달라야 서로 다른 서버가 같은 ms에 만든 값이 겹치지 않는다.
 *               지금은 인스턴스가 하나라 기본값 0을 쓴다. 서버를 늘릴 때 인스턴스마다 다르게 지정한다.
 */
@ConfigurationProperties(prefix = "trace")
public record TraceProperties(
        @DefaultValue("0") int nodeId
) {

    public TraceProperties {
        if (nodeId < 0 || nodeId > TraceIdGenerator.MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    "trace.node-id는 0 이상 " + TraceIdGenerator.MAX_NODE_ID + " 이하여야 합니다. 입력값: " + nodeId);
        }
    }
}
