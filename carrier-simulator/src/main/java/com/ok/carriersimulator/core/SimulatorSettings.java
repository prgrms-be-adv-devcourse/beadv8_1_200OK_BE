package com.ok.carriersimulator.core;

import java.time.Duration;

/**
 * 시뮬레이터를 만들 때의 설정.
 *
 * @param seed          흔들림과 장애 판단에 쓰는 난수 시드
 * @param secondsPerDay 가상 하루를 실제 몇 초로 재생할지
 * @param jitter        송장마다 구간 길이를 흔드는 비율. 0이면 흔들지 않는다
 * @param tokenTtl      토큰 수명
 */
public record SimulatorSettings(long seed, double secondsPerDay, double jitter, Duration tokenTtl,
                                DispatchSettings dispatch) {

    public SimulatorSettings {
        if (!(jitter >= 0 && jitter < 1)) {
            throw new IllegalArgumentException("흔들림 비율은 0 이상 1 미만이어야 합니다.");
        }
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("토큰 수명은 0보다 커야 합니다.");
        }
        if (dispatch == null) {
            throw new IllegalArgumentException("전송 설정은 필수입니다.");
        }
    }
}
