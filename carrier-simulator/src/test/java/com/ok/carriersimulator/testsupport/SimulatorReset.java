package com.ok.carriersimulator.testsupport;

import com.ok.carriersimulator.config.SimulatorProperties;
import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.DispatchSettings;
import com.ok.carriersimulator.core.FaultSettings;
import java.time.Instant;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 테스트마다 시뮬레이터를 처음 상태로 돌려놓는다.
 * {@link SimulatorWebTest} 클래스들은 컨텍스트와 시뮬레이터를 함께 쓰므로, 앞선 테스트가 바꾼 송장과 통계, 재생 속도,
 * 일시정지, 전송 설정, 장애 설정, 토큰이 뒤 테스트로 새지 않게 한다. 테스트 클래스가 따로 초기화하지 않아도 된다.
 */
public class SimulatorReset implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext applicationContext = SpringExtension.getApplicationContext(context);
        CarrierSimulator simulator = applicationContext.getBean(CarrierSimulator.class);
        SimulatorProperties properties = applicationContext.getBean(SimulatorProperties.class);
        Instant now = applicationContext.getBean(MutableClock.class).instant();
        SimulatorProperties.Dispatch dispatch = properties.dispatch();

        simulator.clearAll();
        simulator.updateFaults(FaultSettings.none());
        simulator.updateDispatch(
                new DispatchSettings(dispatch.interval(), dispatch.batchSize(), dispatch.concurrency(), false));
        simulator.resume(now);
        simulator.changeSpeed(properties.secondsPerDay(), now);
        simulator.expireTokens();
    }
}
