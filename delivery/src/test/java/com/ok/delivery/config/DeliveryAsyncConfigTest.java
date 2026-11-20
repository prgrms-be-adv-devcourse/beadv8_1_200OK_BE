package com.ok.delivery.config;

import com.ok.common.config.MdcTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 실행기가 가득 찼을 때 거절하는지는 Spring 없이 실제 스레드 풀로 확인한다. 빈 이름과 기본값은 DeliveryAsyncConfigIntegrationTest가 본다
class DeliveryAsyncConfigTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private ThreadPoolTaskExecutor sut;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (sut != null) {
            sut.shutdown();
        }
    }

    @Test
    void 설정한_스레드_수와_대기열_크기로_실행기를_만든다() {
        // given
        int poolSize = 3;
        int queueCapacity = 20;

        // when
        sut = createExecutor(poolSize, queueCapacity);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getCorePoolSize()).isEqualTo(poolSize);
            softly.assertThat(sut.getMaxPoolSize()).isEqualTo(poolSize);
            softly.assertThat(sut.getQueueCapacity()).isEqualTo(queueCapacity);
            softly.assertThat(sut.getThreadNamePrefix()).isEqualTo("delivery-outbox-");
        });
    }

    @Test
    void 대기열이_가득_차면_넘기는_스레드가_기다리지_않고_바로_거절한다() throws InterruptedException {
        // given: 스레드 1개가 작업을 붙잡고 대기열 1칸도 찬 상태
        sut = createExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        sut.execute(() -> {
            started.countDown();
            await(release);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicInteger ran = new AtomicInteger();
        sut.execute(ran::incrementAndGet);

        // when & then: 기다리게 했다면 release를 풀기 전까지 돌아오지 않는다. 거절은 넘긴 쪽(ShipmentFacade)이 잡아 센다
        assertThatThrownBy(() -> sut.execute(ran::incrementAndGet)).isInstanceOf(TaskRejectedException.class);

        // 거절한 작업은 실행되지 않고, 대기열에 들어간 작업만 실행된다
        release.countDown();
        sut.shutdown();
        assertThat(sut.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void 종료된_실행기에_넘긴_작업도_거절한다() {
        // given
        sut = createExecutor(1, 1);
        sut.shutdown();

        // when & then
        assertThatThrownBy(() -> sut.execute(() -> { })).isInstanceOf(TaskRejectedException.class);
    }

    private ThreadPoolTaskExecutor createExecutor(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new DeliveryAsyncConfig().deliveryOutboxSendExecutor(
                new MdcTaskDecorator(), poolSize, queueCapacity);
        executor.initialize();
        return executor;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
