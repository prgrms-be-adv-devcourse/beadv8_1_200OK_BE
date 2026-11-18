package com.ok.product.product.adapter.in.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ok.product.product.application.port.in.ProductSearchLogRetentionUseCase;

import lombok.RequiredArgsConstructor;

/**
 * 날마다 보존 기간이 지난 검색어 로그를 지운다.
 * 지우기는 여러 번 해도 결과가 같아, 인스턴스가 여럿이어도 잠금 없이 각자 돈다.
 */
@Component
@RequiredArgsConstructor
public class ProductSearchLogRetentionScheduler {

	private final ProductSearchLogRetentionUseCase productSearchLogRetentionUseCase;

	// 검색이 적은 새벽에 돈다. 시간대는 JVM 기본(Asia/Seoul)이다
	@Scheduled(cron = "0 30 4 * * *")
	public void run() {
		productSearchLogRetentionUseCase.deleteExpiredLogs();
	}
}
