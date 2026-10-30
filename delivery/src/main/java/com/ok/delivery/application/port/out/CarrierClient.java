package com.ok.delivery.application.port.out;

/**
 * 택배사에 송장 추적을 요청한다. 택배사는 배송이 끝나면 배송 완료를 알려 온다.
 * 세미 프로젝트는 일정 시간 뒤 배송 완료를 알리는 Mock 구현체를 쓰고, 최종 프로젝트에서 실제 택배사 연동 구현체로 바꾼다.
 */
public interface CarrierClient {

    /**
     * 출고된 송장의 추적을 요청한다.
     *
     * @param carrier 택배사 코드
     */
    void requestTracking(Long shipmentId, String carrier, String trackingNumber);

}
