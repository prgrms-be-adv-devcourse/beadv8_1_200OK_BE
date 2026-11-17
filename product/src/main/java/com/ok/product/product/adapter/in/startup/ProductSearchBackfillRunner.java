package com.ok.product.product.adapter.in.startup;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ok.product.product.application.port.in.ProductSearchBackfillUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 앱이 뜨면 비었거나 낡은 상품 검색 행을 채운다.
 * 마이그레이션으로 채우지 않는 이유는 자모 분해와 품목 찾기가 앱 코드에 있기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSearchBackfillRunner {

	private final ProductSearchBackfillUseCase productSearchBackfillUseCase;

	// 예외를 다시 던지면 앱 전체가 뜨지 못한다. 검색 행은 다음 기동이나 상품 수정 때 다시 채워지므로
	// 검색 색인 하나 때문에 모놀리스 전체를 멈추지 않는다
	// TODO(#704): 동기 실행이라 상품이 많거나 VERSION을 올리면 readiness 전환이 늦어진다. 별도 스레드로 실행한다
	@EventListener(ApplicationReadyEvent.class)
	void backfill() {
		try {
			productSearchBackfillUseCase.backfill();
		} catch (RuntimeException e) {
			log.error("검색 행 백필 실패. 기동은 계속한다", e);
		}
	}
}
