package com.ok.delivery.adapter.out.carrier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// 전체 앱을 띄우지 않고 이 설정만 올려, 알림 주소와 비밀값이 어긋나면 앱 시작이 막히는지 확인한다
class TrackerConfigTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String CALLBACK_URL = "https://delivery.example.com/api/v1/carrier-notifications/";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TrackerConfig.class)
            .withPropertyValues(
                    "delivery.carrier.type=tracker",
                    "delivery.carrier.tracker.base-url=https://api.tracker.example.com/graphql",
                    "delivery.carrier.tracker.auth-url=https://auth.tracker.example.com/token",
                    "delivery.carrier.tracker.client-id=client-id",
                    "delivery.carrier.tracker.client-secret=client-secret",
                    "delivery.carrier.tracker.poll-interval=5m",
                    "delivery.carrier.tracker.connect-timeout=3s",
                    "delivery.carrier.tracker.read-timeout=15s");

    @Test
    void 알림_주소와_비밀값이_맞으면_앱이_시작된다() {
        contextRunner.withPropertyValues(
                        "delivery.carrier.tracker.callback-url=" + CALLBACK_URL + SECRET,
                        "delivery.carrier.webhook.secret=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 알림_주소가_없으면_비밀값이_없어도_앱이_시작된다() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 알림_주소가_있는데_비밀값이_없으면_앱_시작이_막힌다() {
        contextRunner.withPropertyValues("delivery.carrier.tracker.callback-url=" + CALLBACK_URL + SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void 알림_주소의_끝_경로가_비밀값과_다르면_앱_시작이_막힌다() {
        contextRunner.withPropertyValues(
                        "delivery.carrier.tracker.callback-url=" + CALLBACK_URL + "ffffffffffffffffffffffffffffffff",
                        "delivery.carrier.webhook.secret=" + SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }
}
