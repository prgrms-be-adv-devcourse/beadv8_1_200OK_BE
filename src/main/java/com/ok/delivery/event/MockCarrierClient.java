package com.ok.delivery.event;

import com.ok.delivery.service.CarrierClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 추적을 요청받으면 일정 시간 뒤 배송 완료를 알리는 가짜 택배사다.
 * 실제 택배사 연동 전까지 dev와 prod(배포 서버)에서 쓰므로 시연 중에도 배송 완료까지 이어진다.
 * 최종 프로젝트에서 실제 택배사 연동 구현체가 생기면 이 클래스를 지운다.
 */
// 테스트 프로필에서는 등록하지 않는다. 통합 테스트가 출고한 shipment를 시간이 지나 바꾸면 결과가 흔들리기 때문이다
@Profile({"dev", "prod"})
@Slf4j
@Component
@RequiredArgsConstructor
public class MockCarrierClient implements CarrierClient {

    // 시연에서 기다리지 않도록 짧게 둔다
    private static final long DELIVERY_DELAY_SECONDS = 5;

    // 대기는 메모리에서만 하므로, 기다리는 중에 서버가 재시작되면 그 shipment는 배송 중으로 남는다. 시연용 Mock이라 감수한다
    private final Executor delayedExecutor = CompletableFuture.delayedExecutor(DELIVERY_DELAY_SECONDS, TimeUnit.SECONDS);

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void requestTracking(Long shipmentId, String carrier, String trackingNumber) {
        CompletableFuture.runAsync(() -> notifyDelivered(shipmentId), delayedExecutor);
    }

    private void notifyDelivered(Long shipmentId) {
        try {
            applicationEventPublisher.publishEvent(new CarrierDeliveredEvent(shipmentId));
        } catch (RuntimeException e) {
            // 지연 실행 스레드에서 난 예외는 호출한 쪽에 전달되지 않으므로 여기서 남긴다
            log.warn("가짜 택배사의 배송 완료 반영 실패. shipmentId={}", shipmentId, e);
        }
    }
}
