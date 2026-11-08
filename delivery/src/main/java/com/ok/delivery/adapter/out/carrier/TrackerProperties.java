package com.ok.delivery.adapter.out.carrier;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Delivery Tracker 연동 설정. application-delivery.yaml의 delivery.carrier.tracker.* 값이 바인딩된다.
 * 이 설정은 delivery.carrier.type=tracker일 때만 등록되므로, 인증 정보가 비어 있으면 기동 시점에 바로 막는다.
 *
 * @param baseUrl        GraphQL 주소
 * @param authUrl        OAuth2 토큰 발급 주소
 * @param clientId       콘솔에서 발급한 Client ID
 * @param clientSecret   Client Secret. 환경 변수로만 주입하고 저장소에 두지 않는다.
 * @param pollInterval   배송 상태 조회 간격. 무료 플랜은 웹훅이 없어 조회로 배송 완료를 확인한다.
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 대기 타임아웃. 무료 플랜의 일반 추적 큐는 응답이 느릴 수 있어 문서 권장값(15초)을 기본으로 쓴다.
 */
@ConfigurationProperties(prefix = "delivery.carrier.tracker")
record TrackerProperties(
        String baseUrl,
        String authUrl,
        String clientId,
        String clientSecret,
        Duration pollInterval,
        Duration connectTimeout,
        Duration readTimeout
) {
    TrackerProperties {
        if (baseUrl == null || baseUrl.isBlank() || authUrl == null || authUrl.isBlank()) {
            throw new IllegalArgumentException("delivery.carrier.tracker의 base-url, auth-url이 설정되지 않았습니다.");
        }
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "delivery.carrier.tracker의 client-id, client-secret(DELIVERY_TRACKER_CLIENT_ID, DELIVERY_TRACKER_CLIENT_SECRET)이 설정되지 않았습니다.");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
                || connectTimeout == null || readTimeout == null) {
            throw new IllegalArgumentException("delivery.carrier.tracker의 poll-interval, connect-timeout, read-timeout이 올바르지 않습니다.");
        }
    }

    // 레코드의 기본 toString은 모든 필드를 담으므로, 로그나 예외 메시지에 시크릿이 남지 않게 가린다
    @Override
    public String toString() {
        return "TrackerProperties[baseUrl=" + baseUrl
                + ", authUrl=" + authUrl
                + ", clientId=" + clientId
                + ", clientSecret=****"
                + ", pollInterval=" + pollInterval
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
