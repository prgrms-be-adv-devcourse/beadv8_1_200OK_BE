package com.ok.carriersimulator.config;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.DispatchSettings;
import com.ok.carriersimulator.core.SimulatorSettings;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimulatorConfig {

    // core는 현재 시각을 인자로 받는다. 바깥에서 시각을 읽는 곳을 이 빈 하나로 모아 테스트가 시계를 바꿔 끼울 수 있게 한다
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CarrierSimulator carrierSimulator(SimulatorProperties properties, Clock clock) {
        SimulatorProperties.Dispatch dispatch = properties.dispatch();
        SimulatorSettings settings = new SimulatorSettings(properties.seed(), properties.secondsPerDay(),
                properties.jitter(), properties.tokenTtl(),
                new DispatchSettings(dispatch.interval(), dispatch.batchSize(), dispatch.concurrency(), false));
        return new CarrierSimulator(settings, clock.instant());
    }
}
