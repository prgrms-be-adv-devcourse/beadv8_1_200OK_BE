package com.ok.carriersimulator.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 서버를 실제 포트로 띄우는 테스트의 공통 설정.
 * 알림 타이머를 끄고 시계를 테스트가 움직이며, 가상 1시간을 실제 1초로 재생하고 흔들림을 없애 사건 시각을 초 단위로 맞춘다.
 * 컨텍스트를 함께 쓰므로 {@link SimulatorReset}이 테스트마다 시뮬레이터 상태를 처음으로 돌려놓는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "simulator.ticker-enabled=false",
        "simulator.jitter=0",
        "simulator.seconds-per-day=24"
})
@Import({SimulatorWebTest.ClockConfig.class, SimulatorClient.class})
@ExtendWith(SimulatorReset.class)
public @interface SimulatorWebTest {

    @TestConfiguration
    class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
