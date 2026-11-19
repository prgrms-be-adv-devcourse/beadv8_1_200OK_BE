package com.ok.product.product.adapter.in.scheduler;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ok.product.product.application.port.in.ProductTagJudgmentUseCase;

import lombok.RequiredArgsConstructor;

/**
 * 5초마다 상품 태그 판정 대기 요청을 처리한다.
 * {@code app.product-tag.worker.enabled=true}일 때만 켜진다. 리뷰 분석 작업자처럼 운영 설정에서만 켜, 로컬·테스트에서는 Jev를 부르지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.product-tag.worker.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProductTagJudgmentScheduler {

	private final ProductTagJudgmentUseCase productTagJudgmentUseCase;

	// 판정이 길어져도 밀린 실행을 몰아서 하지 않도록 이전 실행이 끝난 때부터 5초를 센다
	@Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
	public void run() {
		productTagJudgmentUseCase.judgePending();
	}
}
