package com.ok.delivery.adapter.out.carrier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ok.delivery.adapter.in.event.CarrierDeliveredEvent;
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

import java.util.List;
import java.util.Map;
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
 * 조회는 메모리에서만 하므로 재시작하면 사라진다. 기동할 때 배송 중인 shipment를 다시 요청해 복구한다(CarrierEventListener).
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
                lastEvent { status { code } }
              }
            }
            """;

    private final RestClient trackerRestClient;
    private final TrackerProperties properties;
    private final ApplicationEventPublisher applicationEventPublisher;

    // 조회는 외부 호출이라 느릴 수 있어 요청 스레드와 분리한다. 스레드 하나로 순서대로 처리하므로 건수가 많아지면 간격을 늘리거나 풀을 키운다
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tracker-carrier-poller");
        thread.setDaemon(true);
        return thread;
    });
    // 같은 shipment가 출고 이벤트와 기동 복구로 두 번 요청돼도 조회는 하나만 돌게 한다
    private final Map<Long, ScheduledFuture<?>> polling = new ConcurrentHashMap<>();
    private String accessToken;

    TrackerCarrierClient(RestClient trackerRestClient, TrackerProperties properties,
                         ApplicationEventPublisher applicationEventPublisher) {
        this.trackerRestClient = trackerRestClient;
        this.properties = properties;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void requestTracking(Long shipmentId, String carrier, String trackingNumber) {
        String carrierId = trackerCarrierId(Carrier.from(carrier));
        long intervalMillis = properties.pollInterval().toMillis();
        polling.computeIfAbsent(shipmentId, id -> scheduler.scheduleWithFixedDelay(
                () -> poll(shipmentId, carrierId, trackingNumber), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS));
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

    void poll(Long shipmentId, String carrierId, String trackingNumber) {
        try {
            if (!isDelivered(carrierId, trackingNumber)) {
                return;
            }
            applicationEventPublisher.publishEvent(new CarrierDeliveredEvent(shipmentId));
            stopPolling(shipmentId);
        } catch (RuntimeException e) {
            // 일시적인 조회·반영 실패는 다음 주기에 다시 시도한다. 예외가 스케줄러까지 가면 이후 조회가 모두 멈춘다
            log.warn("Delivery Tracker 배송 상태 조회·반영 실패. 다음 주기에 다시 시도합니다. shipmentId={}", shipmentId, e);
        }
    }

    private void stopPolling(Long shipmentId) {
        ScheduledFuture<?> future = polling.remove(shipmentId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private boolean isDelivered(String carrierId, String trackingNumber) {
        TrackResponse response = track(carrierId, trackingNumber);
        if (response.hasErrorCode(UNAUTHENTICATED)) {
            invalidateToken();
            response = track(carrierId, trackingNumber);
            if (response.hasErrorCode(UNAUTHENTICATED)) {
                // 새 토큰으로도 인증이 안 되면 만료가 아니라 client secret 오류다. 반복 장애라 경고에 묻히지 않게 error로 남긴다
                log.error("Delivery Tracker 인증 실패. 새 토큰으로도 거부됐습니다. client-id와 client-secret을 확인하세요.");
                return false;
            }
        }
        if (response.hasErrors()) {
            // NOT_FOUND 같은 오류는 message가 비어 올 수 있어 코드도 함께 남긴다
            log.warn("Delivery Tracker 조회 오류 응답. carrierId={}, errors={}", carrierId, response.errorSummaries());
            return false;
        }
        return DELIVERED.equals(response.lastStatusCode());
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

        String lastStatusCode() {
            if (data == null || data.track() == null || data.track().lastEvent() == null
                    || data.track().lastEvent().status() == null) {
                return null;
            }
            return data.track().lastEvent().status().code();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(Track track) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Track(LastEvent lastEvent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LastEvent(Status status) {
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
