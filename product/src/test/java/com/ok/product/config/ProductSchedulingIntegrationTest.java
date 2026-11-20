package com.ok.product.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;

import com.ok.testsupport.AbstractIntegrationTest;

/**
 * product 전용 스케줄러에서 작업자 하나가 오래 걸려도 다른 작업자가 제 간격대로 도는지 확인한다(#788).
 * 실제 작업자는 테스트에서 꺼져 있어(Jev를 부르지 않는다) 같은 스케줄러 빈에 시험용 작업을 같은 방식(fixedDelay)으로 올린다.
 */
class ProductSchedulingIntegrationTest extends AbstractIntegrationTest {

	private static final Duration DELAY = Duration.ofMillis(100);
	// Jev를 부르는 작업자 수(태그 판정, 리뷰 감성·키워드 분석)
	private static final int JEV_WORKER_COUNT = 3;

	@Autowired
	@Qualifier(ProductSchedulingConfig.SCHEDULER)
	private TaskScheduler productTaskScheduler;

	@Test
	void Jev_작업자_셋이_모두_멈춰도_다른_작업자는_제_간격대로_돈다() throws InterruptedException {
		// given
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch blockedStarted = new CountDownLatch(JEV_WORKER_COUNT);
		List<ScheduledFuture<?>> futures = new ArrayList<>();
		AtomicInteger runs = new AtomicInteger();
		Set<String> threadNames = ConcurrentHashMap.newKeySet();
		try {
			// Jev가 응답하지 않아 실행 하나가 끝나지 않는 상황이다
			for (int i = 0; i < JEV_WORKER_COUNT; i++) {
				futures.add(productTaskScheduler.scheduleWithFixedDelay(() -> {
					blockedStarted.countDown();
					awaitQuietly(release);
				}, DELAY));
			}
			assertThat(blockedStarted.await(5, TimeUnit.SECONDS)).isTrue();

			// when
			futures.add(productTaskScheduler.scheduleWithFixedDelay(() -> {
				threadNames.add(Thread.currentThread().getName());
				runs.incrementAndGet();
			}, DELAY));

			// then
			// 간격이 100ms라 막히지 않으면 0.5초 안팎에 5번 돈다. 스레드를 다른 작업자와 나눠 쓰면 한 번도 돌지 못한다
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(runs.get()).isGreaterThanOrEqualTo(5));
			assertThat(threadNames).allMatch(name -> name.startsWith("product-scheduling-"));
		} finally {
			release.countDown();
			futures.forEach(future -> future.cancel(true));
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
