package com.ok.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.SchedulingAwareRunnable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskSchedulerRouter;
import org.springframework.util.StringUtils;

import com.ok.PaldoGotganApplication;
import com.ok.product.config.ProductSchedulingConfig;
import com.ok.testsupport.AbstractIntegrationTest;

/**
 * product 전용 스케줄러를 더해도 다른 모듈의 {@code @Scheduled} 작업은 지금처럼 기본 스케줄러({@code scheduling-1})에서 도는지 확인한다(#788).
 * 모든 모듈이 함께 뜨는 monolith에서만 두 스케줄러가 한 컨텍스트에 있다.
 * 시험용 {@code @Scheduled} 빈을 더하면 컨텍스트가 새로 뜨므로, {@code @Scheduled}가 쓰는 것과 같은 {@link TaskSchedulerRouter}를 이 컨텍스트로 만들어 실제 스레드를 본다.
 */
@SpringBootTest(classes = PaldoGotganApplication.class)
class SchedulerRoutingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BeanFactory beanFactory;

    @Autowired
    private List<ScheduledTaskHolder> scheduledTaskHolders;

    private ScheduledFuture<?> scheduled;

    @AfterEach
    void cancel() {
        if (scheduled != null) {
            scheduled.cancel(true);
        }
    }

    @Test
    void 기본_스케줄러는_Boot_기본값_그대로_스레드_하나다() {
        // when
        ThreadPoolTaskScheduler sut = beanFactory.getBean(TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME,
            ThreadPoolTaskScheduler.class);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPoolSize()).isEqualTo(1);
            softly.assertThat(sut.getThreadNamePrefix()).isEqualTo("scheduling-");
        });
    }

    @Test
    void 스케줄러를_지정하지_않은_작업은_기본_스케줄러_스레드에서_돈다() throws Exception {
        // given
        CompletableFuture<String> threadName = new CompletableFuture<>();

        // when
        scheduled = router().scheduleWithFixedDelay(() -> threadName.complete(Thread.currentThread().getName()),
            Duration.ofMillis(100));

        // then
        assertThat(threadName.get(10, TimeUnit.SECONDS)).isEqualTo("scheduling-1");
    }

    @Test
    void product_스케줄러를_지정한_작업은_product_스케줄러_스레드에서_돈다() throws Exception {
        // given
        CompletableFuture<String> threadName = new CompletableFuture<>();

        // when
        scheduled = router().scheduleWithFixedDelay(
            new QualifiedTask(ProductSchedulingConfig.SCHEDULER,
                () -> threadName.complete(Thread.currentThread().getName())),
            Duration.ofMillis(100));

        // then
        assertThat(threadName.get(10, TimeUnit.SECONDS)).startsWith("product-scheduling-");
    }

    @Test
    void 등록된_스케줄_작업_중_product_작업만_product_스케줄러를_지정한다() {
        // when
        Map<Boolean, List<ScheduledTask>> sut = scheduledTaskHolders.stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .collect(Collectors.partitioningBy(task -> task.toString().startsWith("com.ok.product.")));

        // then
        // 스케줄러를 지정하지 않은 @Scheduled는 이름이 빈 문자열(어노테이션 기본값)이고, 이때 라우터가 기본 스케줄러를 고른다
        // 테스트에서 Jev 작업자는 꺼져 있어 product 작업은 검색어 로그 정리 하나다
        assertThat(sut.get(true)).isNotEmpty()
            .allMatch(task -> ProductSchedulingConfig.SCHEDULER.equals(qualifierOf(task)));
        assertThat(sut.get(false)).isNotEmpty()
            .allMatch(task -> !StringUtils.hasText(qualifierOf(task)));
    }

    // @Scheduled가 쓰는 라우터와 같다. 작업에 스케줄러 이름이 있으면 그 빈을, 없으면 기본 스케줄러를 고른다
    private TaskSchedulerRouter router() {
        TaskSchedulerRouter router = new TaskSchedulerRouter();
        router.setBeanFactory(beanFactory);
        return router;
    }

    private static String qualifierOf(ScheduledTask task) {
        return task.getTask().getRunnable() instanceof SchedulingAwareRunnable runnable ? runnable.getQualifier() : null;
    }

    // @Scheduled(scheduler = ...)로 만든 작업처럼 스케줄러 이름을 들고 있는 작업
    private record QualifiedTask(String qualifier, Runnable delegate) implements SchedulingAwareRunnable {

        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public String getQualifier() {
            return qualifier;
        }
    }
}
