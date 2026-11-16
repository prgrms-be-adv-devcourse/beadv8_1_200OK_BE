package com.ok.product.product.adapter.in.startup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.ok.product.product.application.dto.ProductSearchBackfillResult;
import com.ok.product.product.application.port.in.ProductSearchBackfillUseCase;

class ProductSearchBackfillRunnerTest {

	private final ProductSearchBackfillUseCase productSearchBackfillUseCase = mock(ProductSearchBackfillUseCase.class);
	private final ProductSearchBackfillRunner sut = new ProductSearchBackfillRunner(productSearchBackfillUseCase);

	@Test
	void 기동하면_백필을_맡긴다() {
		// given
		given(productSearchBackfillUseCase.backfill()).willReturn(new ProductSearchBackfillResult(0, 0));

		// when
		sut.backfill();

		// then
		then(productSearchBackfillUseCase).should().backfill();
	}

	@Test
	void 백필이_실패해도_예외를_던지지_않아_기동을_막지_않는다() {
		// given
		given(productSearchBackfillUseCase.backfill()).willThrow(new IllegalStateException("DB 연결 실패"));

		// when & then
		assertThatCode(sut::backfill).doesNotThrowAnyException();
	}
}
