package com.ok.carriersimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Delivery Tracker(tracker.delivery)와 같은 형식으로 답하는 시험용 가짜 택배사 서버다.
 * 운영에 배포하지 않고, delivery의 택배사 연동을 검증할 때만 띄운다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CarrierSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarrierSimulatorApplication.class, args);
    }
}
