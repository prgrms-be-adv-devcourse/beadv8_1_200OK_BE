package com.ok.product.product.adapter.out.persistence;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.ok.product.config.ProductAsyncConfig;
import com.ok.product.product.application.port.out.ProductSearchLogRecorder;
import com.ok.product.product.domain.ProductSearchLog;

import lombok.extern.slf4j.Slf4j;

/**
 * 검색어 로그를 전용 실행기에서 저장한다. 실행기가 가득 차거나 저장이 실패하면 로그를 버리고 WARN만 남긴다.
 * 로그 메시지에는 검색어 원문을 넣지 않는다. 구매자가 개인정보를 검색창에 입력할 수 있다.
 */
@Slf4j
@Component
class ProductSearchLogAsyncRecorder implements ProductSearchLogRecorder {

	private final ProductSearchLogJpaRepository productSearchLogRepository;
	private final Executor executor;

	ProductSearchLogAsyncRecorder(ProductSearchLogJpaRepository productSearchLogRepository,
		@Qualifier(ProductAsyncConfig.SEARCH_LOG_EXECUTOR) Executor executor) {
		this.productSearchLogRepository = productSearchLogRepository;
		this.executor = executor;
	}

	@Override
	public void record(ProductSearchLog searchLog) {
		try {
			executor.execute(() -> save(searchLog));
		} catch (RejectedExecutionException e) {
			log.warn("검색어 로그 실행기가 가득 차 로그를 버렸습니다.");
		}
	}

	private void save(ProductSearchLog searchLog) {
		try {
			productSearchLogRepository.save(searchLog);
		} catch (RuntimeException e) {
			log.warn("검색어 로그를 저장하지 못해 버렸습니다. exception={}", e.getClass().getName());
		}
	}
}
