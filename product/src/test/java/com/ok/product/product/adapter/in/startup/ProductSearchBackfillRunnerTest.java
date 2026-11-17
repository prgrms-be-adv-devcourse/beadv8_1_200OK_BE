package com.ok.product.product.adapter.in.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ok.product.product.application.dto.ProductSearchBackfillResult;
import com.ok.product.product.application.port.in.ProductSearchBackfillUseCase;

class ProductSearchBackfillRunnerTest {

	private final ProductSearchBackfillUseCase productSearchBackfillUseCase = mock(ProductSearchBackfillUseCase.class);

	@Test
	void 기동하면_백필을_맡긴다() {
		// given
		given(productSearchBackfillUseCase.backfill()).willReturn(new ProductSearchBackfillResult(0, 0));
		ProductSearchBackfillRunner sut = new ProductSearchBackfillRunner(productSearchBackfillUseCase, Runnable::run);

		// when
		sut.backfill();

		// then
		then(productSearchBackfillUseCase).should().backfill();
	}

	@Test
	void 리스너는_백필을_실행기에_넘기고_바로_돌아온다() {
		// given
		List<Runnable> submitted = new ArrayList<>();
		ProductSearchBackfillRunner sut = new ProductSearchBackfillRunner(productSearchBackfillUseCase,
			submitted::add);

		// when
		sut.backfill();

		// then
		assertThat(submitted).hasSize(1);
		then(productSearchBackfillUseCase).should(never()).backfill();
	}

	@Test
	void 백필이_실패해도_예외를_던지지_않아_기동을_막지_않는다() {
		// given
		given(productSearchBackfillUseCase.backfill()).willThrow(new IllegalStateException("DB 연결 실패"));
		ProductSearchBackfillRunner sut = new ProductSearchBackfillRunner(productSearchBackfillUseCase, Runnable::run);

		// when & then
		assertThatCode(sut::backfill).doesNotThrowAnyException();
	}
}
