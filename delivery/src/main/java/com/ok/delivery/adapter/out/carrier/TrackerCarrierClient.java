package com.ok.delivery.adapter.out.carrier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ok.delivery.adapter.in.event.CarrierDeliveredEvent;
import com.ok.delivery.adapter.in.event.CarrierStatusReportedEvent;
import com.ok.delivery.adapter.in.event.CarrierTrackingGaveUpEvent;
import com.ok.delivery.application.port.out.CarrierClient;
import com.ok.delivery.domain.Carrier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Delivery Tracker(tracker.delivery)로 배송 상태를 조회해 배송 완료를 알리는 택배사 연동이다.
 *
 * <p>무료 플랜은 웹훅을 제공하지 않아, 추적 요청을 받으면 {@code track}을 일정 간격으로 조회하다가
 * {@code DELIVERED}가 되면 {@link CarrierDeliveredEvent}를 발행하고 조회를 멈춘다.
 * 그 밖의 새 상태는 {@link CarrierStatusReportedEvent}로 한 번씩 발행한다. 같은 상태와 시각은 다시 발행하지 않는다.
 * 출고 후 {@code max-tracking-period}가 지나면 조회를 멈추고 {@link CarrierTrackingGaveUpEvent}를 발행한다.
 * 기준은 저장된 출고 시각이라 재시작해도 기간이 초기화되지 않는다.
 *
 * <p>조회와 마지막 발행 상태는 메모리에서만 가지므로 재시작하면 사라진다. 기동할 때 배송 중인 shipment를 다시 요청해 복구한다(CarrierEventListener).
 * 그래서 재시작하면 같은 마지막 상태가 한 번 더 발행될 수 있다. 수신처가 eventKey로 중복을 무시하는 것이 전제다.
 * 상한을 넘긴 건도 포기 상태를 저장하지 않아 재시작마다 다시 포기 처리된다.
 *
 * <p>인증은 OAuth2 client credentials다. 토큰은 만료되므로(응답 본문의 {@code UNAUTHENTICATED}) 새로 받아 한 번 다시 시도한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "delivery.carrier.type", havingValue = "tracker")
public class TrackerCarrierClient implements CarrierClient, DisposableBean {

    private static final String DELIVERED = "DELIVERED";
    private static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String TRACK_QUERY = """
            query Track($carrierId: ID!, $trackingNumber: String!) {
              track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
                lastEvent { time status { code } }
              }
            }
            """;

    private final RestClient trackerRestClient;
    private final TrackerProperties properties;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    // 조회는 외부 호출이라 느릴 수 있어 요청 스레드와 분리한다. 스레드 하나로 순서대로 처리하므로 건수가 많아지면 간격을 늘리거나 풀을 키운다
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tracker-carrier-poller");
        thread.setDaemon(true);
        return thread;
    });
    // 같은 shipment가 출고 이벤트와 기동 복구로 두 번 요청돼도 조회는 하나만 돌게 한다
    private final Map<Long, ScheduledFuture<?>> polling = new ConcurrentHashMap<>();
    // shipment마다 마지막으로 발행한 상태 eventKey. 같은 상태와 시각이 다시 조회돼도 한 번만 발행한다
    private final Map<Long, String> lastReportedKeys = new ConcurrentHashMap<>();
    // 포기 처리한 shipment. 같은 shipment가 다시 요청돼도 포기 이벤트는 한 번만 발행한다
    private final Set<Long> gaveUp = ConcurrentHashMap.newKeySet();
    private String accessToken;

    TrackerCarrierClient(RestClient trackerRestClient, TrackerProperties properties,
                         ApplicationEventPublisher applicationEventPublisher, Clock clock) {
        this.trackerRestClient = trackerRestClient;
        this.properties = properties;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = clock;
    }

    @Override
    public void requestTracking(Long shipmentId, String carrier, String trackingNumber, LocalDateTime shippedAt) {
        String carrierId = trackerCarrierId(Carrier.from(carrier));
        if (isOverLimit(shippedAt)) {
            giveUp(shipmentId, shippedAt);
            return;
        }
        long intervalMillis = properties.pollInterval().toMillis();
        polling.computeIfAbsent(shipmentId, id -> scheduler.scheduleWithFixedDelay(
                () -> poll(shipmentId, carrierId, trackingNumber, shippedAt), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS));
    }

    // 새 택배사를 enum에 추가하면 여기서 컴파일 오류가 나서 Delivery Tracker의 carrierId 매핑을 빠뜨리지 않는다
    static String trackerCarrierId(Carrier carrier) {
        return switch (carrier) {
            case CJ_LOGISTICS -> "kr.cjlogistics";
            case POST_OFFICE -> "kr.epost";
            case HANJIN -> "kr.hanjin";
            case LOTTE -> "kr.lotte";
            case LOGEN -> "kr.logen";
        };
    }

    void poll(Long shipmentId, String carrierId, String trackingNumber, LocalDateTime shippedAt) {
        try {
            // 상한은 기간으로 판정한다. 오류 응답이 몇 번 왔는지와 무관하게, 넘으면 HTTP 호출 없이 멈춘다
            if (isOverLimit(shippedAt)) {
                giveUp(shipmentId, shippedAt);
                return;
            }
            LastEvent lastEvent = fetchLastEvent(carrierId, trackingNumber);
            if (lastEvent == null) {
                return;
            }
            if (DELIVERED.equals(lastEvent.statusCode())) {
                applicationEventPublisher.publishEvent(new CarrierDeliveredEvent(shipmentId));
                stopPolling(shipmentId);
                return;
            }
            reportStatus(shipmentId, lastEvent);
        } catch (RuntimeException e) {
            // 일시적인 조회·반영 실패는 다음 주기에 다시 시도한다. 예외가 스케줄러까지 가면 이후 조회가 모두 멈춘다
            log.warn("Delivery Tracker 배송 상태 조회·반영 실패. 다음 주기에 다시 시도합니다. shipmentId={}", shipmentId, e);
        }
    }

    private boolean isOverLimit(LocalDateTime shippedAt) {
        return LocalDateTime.now(clock).isAfter(shippedAt.plus(properties.maxTrackingPeriod()));
    }

    // 알림은 CarrierEventListener가 ERROR 로그로 남긴다. 여기서는 로그를 중복으로 남기지 않는다
    // 요청 스레드, 기동 복구, 스케줄러가 같은 shipment를 동시에 포기 처리할 수 있어 add로 한 번만 통과시킨다.
    // 발행이 실패하면 되돌려, 예외가 poll의 catch에서 삼켜진 뒤 다음 주기에 다시 알리게 한다
    private void giveUp(Long shipmentId, LocalDateTime shippedAt) {
        if (gaveUp.add(shipmentId)) {
            try {
                applicationEventPublisher.publishEvent(
                        new CarrierTrackingGaveUpEvent(shipmentId, shippedAt, properties.maxTrackingPeriod()));
            } catch (RuntimeException e) {
                gaveUp.remove(shipmentId);
                throw e;
            }
        }
        stopPolling(shipmentId);
    }

    private void reportStatus(Long shipmentId, LastEvent lastEvent) {
        CarrierStatusReportedEvent event = new CarrierStatusReportedEvent(shipmentId, lastEvent.statusCode(), lastEvent.time());
        if (event.eventKey().equals(lastReportedKeys.get(shipmentId))) {
            return;
        }
        applicationEventPublisher.publishEvent(event);
        lastReportedKeys.put(shipmentId, event.eventKey());
    }

    private void stopPolling(Long shipmentId) {
        lastReportedKeys.remove(shipmentId);
        ScheduledFuture<?> future = polling.remove(shipmentId);
        if (future != null) {
            future.cancel(false);
        }
    }

    // 조회를 멈췄는지 테스트에서 확인하려고 둔다. 상한을 넘으면 어차피 HTTP를 부르지 않아 호출 횟수로는 멈춤을 구분할 수 없다
    boolean isPolling(Long shipmentId) {
        return polling.containsKey(shipmentId);
    }

    // 오류 응답, 인증 실패, 이벤트 없음은 상태가 없으므로 null이다
    private LastEvent fetchLastEvent(String carrierId, String trackingNumber) {
        TrackResponse response = track(carrierId, trackingNumber);
        if (response.hasErrorCode(UNAUTHENTICATED)) {
            invalidateToken();
            response = track(carrierId, trackingNumber);
            if (response.hasErrorCode(UNAUTHENTICATED)) {
                // 새 토큰으로도 인증이 안 되면 만료가 아니라 client secret 오류다. 반복 장애라 경고에 묻히지 않게 error로 남긴다
                log.error("Delivery Tracker 인증 실패. 새 토큰으로도 거부됐습니다. client-id와 client-secret을 확인하세요.");
                return null;
            }
        }
        if (response.hasErrors()) {
            // NOT_FOUND 같은 오류는 message가 비어 올 수 있어 코드도 함께 남긴다
            log.warn("Delivery Tracker 조회 오류 응답. carrierId={}, errors={}", carrierId, response.errorSummaries());
            return null;
        }
        LastEvent lastEvent = response.lastEvent();
        return lastEvent == null || lastEvent.statusCode() == null ? null : lastEvent;
    }

    private TrackResponse track(String carrierId, String trackingNumber) {
        TrackResponse response = trackerRestClient.post()
                .uri(properties.baseUrl())
                .header("Authorization", "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", TRACK_QUERY,
                        "variables", Map.of("carrierId", carrierId, "trackingNumber", trackingNumber)))
                .retrieve()
                .body(TrackResponse.class);
        return response == null ? new TrackResponse(null, List.of()) : response;
    }

    private synchronized String accessToken() {
        if (accessToken == null) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());
            TokenResponse response = trackerRestClient.post()
                    .uri(properties.authUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new IllegalStateException("Delivery Tracker 토큰 응답에 access_token이 없습니다.");
            }
            accessToken = response.accessToken();
        }
        return accessToken;
    }

    private synchronized void invalidateToken() {
        accessToken = null;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    // GraphQL은 오류도 HTTP 200 본문의 errors로 오므로 상태 코드가 아니라 본문으로 판단한다
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrackResponse(Data data, List<GraphQlError> errors) {

        boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }

        boolean hasErrorCode(String code) {
            return hasErrors() && errors.stream().anyMatch(error -> code.equals(error.code()));
        }

        List<String> errorSummaries() {
            return errors.stream().map(error -> error.code() + ": " + error.message()).toList();
        }

        LastEvent lastEvent() {
            return data == null || data.track() == null ? null : data.track().lastEvent();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(Track track) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Track(LastEvent lastEvent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LastEvent(String time, Status status) {

        String statusCode() {
            return status == null ? null : status.code();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Status(String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GraphQlError(String message, Map<String, Object> extensions) {

        String code() {
            Object code = extensions == null ? null : extensions.get("code");
            return code == null ? null : code.toString();
        }
    }
}
