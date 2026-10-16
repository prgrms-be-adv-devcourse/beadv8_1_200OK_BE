package com.ok.wallet.client.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 토스페이먼츠 API 설정. application.yaml의 toss.payments.* 값이 바인딩된다.
 * 시크릿 키가 비어 있으면 충전 승인이 런타임에 실패하므로, 기동 시점에 바로 막는다 (JwtProperties와 같은 방식).
 *
 * @param baseUrl        토스 API 주소
 * @param secretKey      시크릿 키. 환경 변수로만 주입하고 저장소에 두지 않는다.
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 대기 타임아웃. 넘으면 승인 결과를 알 수 없는 것으로 본다.
 */
@ConfigurationProperties(prefix = "toss.payments")
public record TossPaymentsProperties(
        String baseUrl,
        String secretKey,
        Duration connectTimeout,
        Duration readTimeout
) {
    public TossPaymentsProperties {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("toss.payments.secret-key(TOSS_SECRET_KEY)가 설정되지 않았습니다.");
        }
    }
}
