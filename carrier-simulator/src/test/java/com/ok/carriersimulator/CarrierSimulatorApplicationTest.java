package com.ok.carriersimulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.ok.carriersimulator.config.SimulatorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CarrierSimulatorApplicationTest {

    @Autowired
    private SimulatorProperties properties;

    @Test
    void 컨텍스트가_뜬다() {
        // then
        assertThat(properties.dispatch().batchSize()).isEqualTo(100);
    }
}
