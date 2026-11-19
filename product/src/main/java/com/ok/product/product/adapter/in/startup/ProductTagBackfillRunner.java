package com.ok.product.product.adapter.in.startup;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ok.product.config.ProductAsyncConfig;
import com.ok.product.product.application.port.in.ProductTagBackfillUseCase;

import lombok.extern.slf4j.Slf4j;

/**
 * 앱이 뜨면 판정을 받아야 하는 상품(판정 행 없음, 태그 목록 버전이 바뀜, 최종 실패)을 판정 대기열에 넣는다.
 * 판정은 작업자가 하고 여기서는 대기열만 채운다. 마이그레이션으로 채우지 않는 이유는 태그 목록 버전이 앱 리소스에 있기 때문이다.
 */
@Slf4j
@Component
public class ProductTagBackfillRunner {

	private final ProductTagBackfillUseCase productTagBackfillUseCase;
	private final Executor executor;

	// 검색 행 백필과 같은 실행기(스레드 1개, 대기열 1개)를 써서 두 백필이 겹쳐 DB를 함께 잡지 않게 한다. 기동 때 한 번씩만 넣어 대기열이 넘치지 않는다
	public ProductTagBackfillRunner(ProductTagBackfillUseCase productTagBackfillUseCase,
		@Qualifier(ProductAsyncConfig.SEARCH_BACKFILL_EXECUTOR) Executor executor) {
		this.productTagBackfillUseCase = productTagBackfillUseCase;
		this.executor = executor;
	}

	// 예외를 다시 던지면 앱 전체가 뜨지 못한다. 대기열은 다음 기동이나 상품 수정 때 다시 채워지므로 모놀리스 전체를 멈추지 않는다
	@EventListener(ApplicationReadyEvent.class)
	void backfill() {
		executor.execute(this::runBackfill);
	}

	private void runBackfill() {
		try {
			productTagBackfillUseCase.backfill();
		} catch (RuntimeException e) {
			log.error("상품 태그 판정 백필 실패. 기동은 계속한다", e);
		}
	}
}
