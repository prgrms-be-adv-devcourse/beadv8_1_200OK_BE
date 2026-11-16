package com.ok.delivery.adapter.out.carrier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackerPropertiesWebhookSecretTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String CALLBACK_URL = "https://delivery.example.com/api/v1/carrier-notifications/" + SECRET;
    private static final String MISSING_SECRET_MESSAGE =
            "delivery.carrier.tracker의 callback-url을 설정하면 delivery.carrier.webhook.secret(DELIVERY_CARRIER_WEBHOOK_SECRET)도 설정해야 합니다.";
    private static final String SHORT_SECRET_MESSAGE = "delivery.carrier.webhook.secret은 32자 이상이어야 합니다.";
    private static final String PATH_MISMATCH_MESSAGE =
            "delivery.carrier.tracker의 callback-url 끝 경로가 delivery.carrier.webhook.secret과 같아야 합니다.";

    @Test
    void 알림_주소와_비밀값이_맞으면_통과한다() {
        // given
        TrackerProperties sut = properties(CALLBACK_URL);

        // when & then
        assertThatCode(() -> sut.requireMatchingWebhookSecret(SECRET)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("알림_주소가_비어_있는_설정")
    void 알림_주소가_비어_있으면_비밀값이_없어도_통과한다(String callbackUrl, String webhookSecret) {
        // given
        TrackerProperties sut = properties(callbackUrl);

        // when & then
        assertThatCode(() -> sut.requireMatchingWebhookSecret(webhookSecret)).doesNotThrowAnyException();
    }

    static Stream<Object[]> 알림_주소가_비어_있는_설정() {
        return Stream.of(
                new Object[]{null, null},
                new Object[]{"", ""},
                new Object[]{"   ", "short"});
    }

    @ParameterizedTest
    @MethodSource("비어_있는_비밀값")
    void 알림_주소가_있는데_비밀값이_비어_있으면_기동을_막는다(String webhookSecret) {
        // given
        TrackerProperties sut = properties(CALLBACK_URL);

        // when & then
        assertThatThrownBy(() -> sut.requireMatchingWebhookSecret(webhookSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MISSING_SECRET_MESSAGE);
    }

    static Stream<String> 비어_있는_비밀값() {
        return Stream.of(null, "", "   ");
    }

    @ParameterizedTest
    @MethodSource("짧은_비밀값")
    void 알림_주소가_있는데_비밀값이_짧으면_기동을_막는다(String webhookSecret) {
        // given
        TrackerProperties sut = properties(CALLBACK_URL);

        // when & then
        assertThatThrownBy(() -> sut.requireMatchingWebhookSecret(webhookSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SHORT_SECRET_MESSAGE);
    }

    static Stream<String> 짧은_비밀값() {
        return Stream.of("short", SECRET.substring(1));
    }

    @ParameterizedTest
    @MethodSource("끝_경로가_비밀값과_다른_주소")
    void 알림_주소의_끝_경로가_비밀값과_다르면_기동을_막는다(String callbackUrl) {
        // given
        TrackerProperties sut = properties(callbackUrl);

        // when & then
        assertThatThrownBy(() -> sut.requireMatchingWebhookSecret(SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PATH_MISMATCH_MESSAGE);
    }

    static Stream<String> 끝_경로가_비밀값과_다른_주소() {
        return Stream.of(
                "https://delivery.example.com/api/v1/carrier-notifications/ffffffffffffffffffffffffffffffff",
                "https://delivery.example.com/api/v1/carrier-notifications",
                "https://delivery.example.com",
                // 알림 API는 경로 끝에 /가 붙으면 받지 못한다
                CALLBACK_URL + "/",
                // 쿼리에 비밀값이 있어도 경로가 아니므로 맞지 않는다
                "https://delivery.example.com/api/v1/carrier-notifications?secret=" + SECRET,
                "https://delivery example.com/" + SECRET);
    }

    @Test
    void 오류_메시지에는_알림_주소와_비밀값을_싣지_않는다() {
        // given
        TrackerProperties sut = properties(CALLBACK_URL);
        String wrongSecret = "ffffffffffffffffffffffffffffffff";

        // when & then
        assertThatThrownBy(() -> sut.requireMatchingWebhookSecret(wrongSecret))
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain(wrongSecret)
                        .doesNotContain(SECRET)
                        .doesNotContain("delivery.example.com"));
    }

    private TrackerProperties properties(String callbackUrl) {
        return new TrackerProperties("https://api.tracker.example.com/graphql", "https://auth.tracker.example.com/token",
                "client-id", "client-secret", Duration.ofMinutes(5), callbackUrl, Duration.ofHours(48),
                Duration.ofSeconds(3), Duration.ofSeconds(15));
    }
}
