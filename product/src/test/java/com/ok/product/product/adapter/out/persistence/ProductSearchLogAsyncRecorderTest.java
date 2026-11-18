package com.ok.product.product.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSearchLog;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ProductSearchLogAsyncRecorderTest {

	@Mock
	ProductSearchLogJpaRepository productSearchLogRepository;

	@Test
	void 실행기가_가득_차면_기다리지_않고_로그를_버리고_WARN을_남긴다(CapturedOutput output) throws Exception {
		// given
		ThreadPoolTaskExecutor executor = executor(1, 1);
		CountDownLatch saving = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		given(productSearchLogRepository.save(any())).willAnswer(invocation -> {
			saving.countDown();
			release.await();
			return invocation.getArgument(0);
		});
		ProductSearchLogAsyncRecorder sut = new ProductSearchLogAsyncRecorder(productSearchLogRepository, executor);
		sut.record(log("저장 중"));
		assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
		sut.record(log("대기 중"));

		// when
		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> sut.record(log("넘친 검색")));

		// then
		release.countDown();
		executor.shutdown();
		ArgumentCaptor<ProductSearchLog> captor = ArgumentCaptor.forClass(ProductSearchLog.class);
		then(productSearchLogRepository).should(times(2)).save(captor.capture());
		assertThat(captor.getAllValues()).extracting(ProductSearchLog::getKeyword).containsExactly("저장 중", "대기 중");
		assertThat(output.getOut()).contains("검색어 로그 실행기가 가득 차 로그를 버렸습니다").doesNotContain("넘친 검색");
	}

	@Test
	void 저장이_실패해도_예외를_던지지_않고_검색어_원문_없이_WARN만_남긴다(CapturedOutput output) {
		// given
		String keyword = "제주 하우스감귤";
		given(productSearchLogRepository.save(any()))
			.willThrow(new DataAccessResourceFailureException("저장 실패 " + keyword));
		ProductSearchLogAsyncRecorder sut = new ProductSearchLogAsyncRecorder(productSearchLogRepository, Runnable::run);

		// when & then
		assertThatCode(() -> sut.record(log(keyword))).doesNotThrowAnyException();
		assertThat(output.getOut()).contains("검색어 로그를 저장하지 못해 버렸습니다").doesNotContain(keyword, "하우스감귤");
	}

	// 종료할 때 대기열의 작업을 마저 돌려, 저장 횟수를 확인하기 전에 모든 작업이 끝나게 한다
	private ThreadPoolTaskExecutor executor(int poolSize, int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(5);
		executor.initialize();
		return executor;
	}

	private ProductSearchLog log(String keyword) {
		return ProductSearchLog.of(keyword, ProductSearchKeyword.from(keyword).orElseThrow(), 1, 10, false,
			LocalDateTime.of(2026, 11, 18, 10, 0));
	}
}
