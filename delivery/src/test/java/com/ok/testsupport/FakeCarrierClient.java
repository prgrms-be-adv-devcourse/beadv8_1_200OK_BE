package com.ok.testsupport;

import com.ok.delivery.application.port.out.CarrierClient;
import com.ok.delivery.domain.Carrier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 통합 테스트에서 택배사 연동을 대신한다. 추적 요청을 받아도 아무 일도 하지 않아 배송 완료를 알리지 않는다.
 * 알림 수신 테스트는 {@link #markDelivered}로 택배사가 배송 완료로 답할 송장을 정하고, 끝나면 {@link #reset}으로 비운다.
 * 택배사 식별자는 {@link Carrier} 이름 그대로 쓴다. 실제 식별자 매핑은 TrackerCarrierClientTest가 확인한다.
 *
 * <p>운영의 Mock 택배사({@code MockCarrierClient})는 test 프로필에서 등록되지 않는다.
 * 시간이 지나 shipment를 배송 완료로 바꾸면 통합 테스트 결과가 흔들리기 때문이다.
 *
 * 운영 {@code MockCarrierClient}는 test 프로필에서 등록되지 않으므로 빈이 겹치지 않는다.
 * 모듈 테스트({@code DeliveryModuleTest})는 컴포넌트 스캔을 하지 않아 {@code @Import}로 직접 등록한다.
 */
@Component
public class FakeCarrierClient implements CarrierClient {

    private final Set<String> deliveredTrackingNumbers = ConcurrentHashMap.newKeySet();

    @Override
    public void requestTracking(Long shipmentId, String carrier, String trackingNumber) {
    }

    @Override
    public Optional<Carrier> findCarrier(String carrierId) {
        return Arrays.stream(Carrier.values()).filter(carrier -> carrier.name().equals(carrierId)).findFirst();
    }

    @Override
    public boolean isDelivered(Carrier carrier, String trackingNumber) {
        return deliveredTrackingNumbers.contains(trackingNumber);
    }

    public void markDelivered(String trackingNumber) {
        deliveredTrackingNumbers.add(trackingNumber);
    }

    public void reset() {
        deliveredTrackingNumbers.clear();
    }
}
