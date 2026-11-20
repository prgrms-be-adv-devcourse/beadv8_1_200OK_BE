package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.SellerShipmentSummaryResult;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentDetailResult;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.delivery.application.dto.ShipmentDispatchResult;
import com.ok.delivery.application.dto.ShipmentSummaryResult;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import com.ok.delivery.application.port.out.CarrierClient;
import com.ok.delivery.config.DeliveryAsyncConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 배송 유스케이스를 트랜잭션 밖에서 감싸, {@link ShipmentService}의 트랜잭션이 커밋된 뒤에 할 일을 한다.
 * 배송 생성·출고·배송 완료가 커밋되면 서비스가 남긴 outbox 줄을 배송 전용 실행기로 넘겨 바로 Kafka로 보내고 (#796),
 * 출고면 택배사에 송장 추적을 요청한다. 브로커와 택배사는 외부라 트랜잭션 안에서 부르지 않는다.
 * 그 밖의 유스케이스는 서비스에 그대로 맡긴다. 입력 포트를 하나로 두려고 이 Facade가 함께 구현한다.
 *
 * <p>서비스가 돌아오면 트랜잭션이 끝나 DB 커넥션도 반납된 뒤다(모든 모듈이 {@code open-in-view: false}).
 * 그래서 대기열이 가득 차 거절돼도 커넥션을 쥔 채 처리하는 코드가 없다. 진입점이 트랜잭션 안에서 이 Facade를 부르면 이 전제가 깨진다.
 */
@Slf4j
@Component
public class ShipmentFacade implements ShipmentUseCase {

    private final ShipmentService shipmentService;
    private final DeliveryOutboxRelayFacade deliveryOutboxRelayFacade;
    private final CarrierClient carrierClient;
    private final Clock clock;
    private final Counter rejectedDropped;

    public ShipmentFacade(ShipmentService shipmentService,
                          DeliveryOutboxRelayFacade deliveryOutboxRelayFacade,
                          CarrierClient carrierClient,
                          Clock clock,
                          MeterRegistry meterRegistry) {
        this.shipmentService = shipmentService;
        this.deliveryOutboxRelayFacade = deliveryOutboxRelayFacade;
        this.carrierClient = carrierClient;
        this.clock = clock;
        this.rejectedDropped = Counter.builder(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_METRIC)
                .description(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_DESCRIPTION)
                .tag("reason", DeliveryAsyncConfig.DROP_REASON_REJECTED)
                .register(meterRegistry);
    }

    @Override
    public void create(ShipmentCreateCommand command) {
        send(shipmentService.create(command));
    }

    @Override
    public ShipmentDispatchResult dispatch(ShipmentDispatchCommand command) {
        ShipmentDispatchResult result = shipmentService.dispatch(command);
        send(List.of(result.outboxId()));
        requestTracking(result);
        return result;
    }

    @Override
    public void deliver(Long shipmentId) {
        send(List.of(shipmentService.deliver(shipmentId)));
    }

    @Override
    public void confirmPurchase(Long orderItemId) {
        shipmentService.confirmPurchase(orderItemId);
    }

    @Override
    public Page<ShipmentSummaryResult> getBuyerShipments(Long buyerId, int page) {
        return shipmentService.getBuyerShipments(buyerId, page);
    }

    @Override
    public Page<SellerShipmentSummaryResult> getSellerShipments(Long sellerId, int page) {
        return shipmentService.getSellerShipments(sellerId, page);
    }

    @Override
    public ShipmentDetailResult getBuyerShipment(Long buyerId, Long shipmentId) {
        return shipmentService.getBuyerShipment(buyerId, shipmentId);
    }

    @Override
    public ShipmentDetailResult getSellerShipment(Long sellerId, Long shipmentId) {
        return shipmentService.getSellerShipment(sellerId, shipmentId);
    }

    // 기다린 시간은 발송 스레드가 같은 Clock으로 잰다. 줄의 createdAt은 JPA 감사가 Clock 없이 채워 기준이 어긋날 수 있다
    private void send(List<Long> outboxIds) {
        Instant recordedAt = clock.instant();
        for (Long outboxId : outboxIds) {
            try {
                deliveryOutboxRelayFacade.send(outboxId, recordedAt);
            } catch (TaskRejectedException e) {
                // 줄은 PENDING으로 남아 발송 작업이 보낸다. 몰릴 때 건마다 남지 않게 로그 대신 지표로 센다 (#793)
                rejectedDropped.increment();
            }
        }
    }

    // 출고는 이미 커밋됐으므로 요청이 실패해도 출고 응답을 실패로 바꾸지 않는다. 놓친 요청은 앱이 뜰 때 배송 중인 shipment를 다시 요청해 채운다(CarrierEventListener)
    private void requestTracking(ShipmentDispatchResult result) {
        try {
            carrierClient.requestTracking(result.shipmentId(), result.carrier(), result.trackingNumber());
        } catch (RuntimeException e) {
            log.warn("택배사 추적 요청 실패, 재시작 때 다시 요청함. shipmentId={}", result.shipmentId(), e);
        }
    }
}
