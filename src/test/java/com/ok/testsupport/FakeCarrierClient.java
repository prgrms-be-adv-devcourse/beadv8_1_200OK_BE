package com.ok.testsupport;

import com.ok.delivery.service.CarrierClient;

/**
 * 통합 테스트에서 택배사 연동을 대신한다. 추적 요청을 받아도 아무 일도 하지 않아 배송 완료를 알리지 않는다.
 *
 * <p>운영의 Mock 택배사({@code MockCarrierClient})는 test 프로필에서 등록되지 않는다.
 * 시간이 지나 shipment를 배송 완료로 바꾸면 통합 테스트 결과가 흔들리기 때문이다.
 *
 * <p>클래스에 {@code @Component}를 붙이지 않는 이유는 {@link TestClockConfiguration}과 같다.
 * {@link AbstractIntegrationTest}가 {@code @Import}로 한 번만 등록한다.
 */
public class FakeCarrierClient implements CarrierClient {

    @Override
    public void requestTracking(Long shipmentId, String carrier, String trackingNumber) {
    }
}
