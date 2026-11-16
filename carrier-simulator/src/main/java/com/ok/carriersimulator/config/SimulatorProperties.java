package com.ok.carriersimulator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 가짜 택배사의 시작 설정. 재생 속도, 전송 속도, 장애 설정은 실행 중에 관리 화면에서 바꿀 수 있고, 여기 값은 처음 값이다.
 *
 * @param seed           흔들림과 장애 판단에 쓰는 난수 시드. 같은 시드면 같은 흐름이 나온다
 * @param secondsPerDay  가상 하루를 실제 몇 초로 재생할지
 * @param jitter         송장마다 구간 길이를 흔드는 비율(0.1이면 ±10%). 더미 송장이 한꺼번에 같은 순간에 바뀌지 않게 한다
 * @param tokenTtl       발급한 토큰의 수명
 * @param dispatch       알림 전송 속도
 * @param tickerEnabled  알림 전송 타이머를 켤지. 테스트는 꺼 두고 직접 한 번씩 돌린다
 */
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        @DefaultValue("1") long seed,
        @DefaultValue("10") double secondsPerDay,
        @DefaultValue("0.1") double jitter,
        @DefaultValue("1h") Duration tokenTtl,
        @DefaultValue Dispatch dispatch,
        @DefaultValue("true") boolean tickerEnabled
) {

    /**
     * @param interval    대기 줄에서 알림을 꺼내는 간격
     * @param batchSize   한 번에 꺼내는 개수
     * @param concurrency 동시에 보내는 수
     */
    public record Dispatch(
            @DefaultValue("1s") Duration interval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("10") int concurrency
    ) {
    }
}
