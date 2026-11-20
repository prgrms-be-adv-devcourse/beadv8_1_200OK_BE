package com.ok.delivery.config;

import com.ok.testsupport.AbstractIntegrationTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: @Async가 찾는 이름으로 배송 전용 실행기가 등록되고, 설정이 없을 때 기본값(스레드 4, 대기열 20)을 쓰며,
// 운영에서 볼 대기열 길이 지표가 붙는지 확인 (#793)
class DeliveryAsyncConfigIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier(DeliveryAsyncConfig.OUTBOX_SEND_EXECUTOR)
    ThreadPoolTaskExecutor sut;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void 즉시_발송_실행기는_전용_이름과_기본값으로_등록된다() {
        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getCorePoolSize()).isEqualTo(4);
            softly.assertThat(sut.getMaxPoolSize()).isEqualTo(4);
            softly.assertThat(sut.getQueueCapacity()).isEqualTo(20);
            softly.assertThat(sut.getThreadNamePrefix()).isEqualTo("delivery-outbox-");
        });
    }

    @Test
    void 즉시_발송_대기열_길이를_지표로_노출한다() {
        // when
        Gauge queued = meterRegistry.find("executor.queued").tag("name", DeliveryAsyncConfig.OUTBOX_SEND_EXECUTOR).gauge();

        // then
        assertThat(queued).isNotNull();
    }
}
