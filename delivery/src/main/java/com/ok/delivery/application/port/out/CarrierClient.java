package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.Carrier;

import java.util.Optional;

/**
 * 택배사에 송장 추적을 요청한다. 택배사는 배송이 끝나면 배송 완료를 알려 온다.
 * 세미 프로젝트는 일정 시간 뒤 배송 완료를 알리는 Mock 구현체를 쓰고, 최종 프로젝트에서 실제 택배사 연동 구현체로 바꾼다.
 */
public interface CarrierClient {

    /**
     * 출고된 송장의 추적을 요청한다.
     * 출고 요청 스레드에서 부르므로 택배사 호출을 기다리지 않고 바로 돌아온다. 호출은 구현체가 자기 스레드에서 한다.
     *
     * @param carrier 택배사 코드
     */
    void requestTracking(Long shipmentId, String carrier, String trackingNumber);

    /**
     * 택배사가 알림에 담아 보낸 택배사 식별자를 {@link Carrier}로 되돌린다.
     * 식별자 형식은 택배사 연동마다 달라 이 포트가 한곳에서 맡는다. 모르는 식별자면 비어 있다.
     */
    Optional<Carrier> findCarrier(String carrierId);

    /**
     * 택배사에 송장 상태를 조회해 배송 완료인지 확인한다. 외부 호출이라 DB 트랜잭션 밖에서 호출한다.
     * 조회 오류 응답은 배송 완료가 아닌 것으로 본다. 네트워크 오류는 예외로 전달된다.
     */
    boolean isDelivered(Carrier carrier, String trackingNumber);

}
