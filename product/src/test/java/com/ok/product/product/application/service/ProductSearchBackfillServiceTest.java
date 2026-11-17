package com.ok.product.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.ok.product.product.application.dto.ProductSearchBackfillResult;
import com.ok.product.product.application.port.out.ProductRepository;
import com.ok.product.product.application.port.out.ProductSearchRepository;
import com.ok.product.product.domain.Product;

class ProductSearchBackfillServiceTest {

	private static final int BATCH_SIZE = 30;

	private final ProductSearchRepository productSearchRepository = mock(ProductSearchRepository.class);
	private final ProductRepository productRepository = mock(ProductRepository.class);
	private final ProductSearchIndexer productSearchIndexer = mock(ProductSearchIndexer.class);
	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private final ProductSearchBackfillService sut = new ProductSearchBackfillService(productSearchRepository,
		productRepository, productSearchIndexer, transactionManager);

	@Test
	void 묶음의_모든_건이_실패하면_연속_실패_상한까지만_건별로_재시도한다() {
		// given
		List<Long> ids = LongStream.rangeClosed(1, BATCH_SIZE).boxed().toList();
		givenOneBatch(ids);
		given(productRepository.findAllWithOptionsByIdIn(anyList())).willThrow(new IllegalStateException("DB 장애"));

		// when
		ProductSearchBackfillResult result = sut.backfill(BATCH_SIZE);

		// then
		assertThat(result).isEqualTo(new ProductSearchBackfillResult(0, BATCH_SIZE));
		// 묶음 시도 1번 + 건별 재시도 10번
		verify(productRepository, times(1 + 10)).findAllWithOptionsByIdIn(anyList());
	}

	@Test
	void 중간의_한_건만_실패하면_나머지는_모두_처리한다() {
		// given
		List<Long> ids = LongStream.rangeClosed(1, BATCH_SIZE).boxed().toList();
		givenOneBatch(ids);
		given(productRepository.findAllWithOptionsByIdIn(anyList())).willAnswer(invocation -> {
			List<Long> requested = invocation.getArgument(0);
			if (requested.contains(15L)) {
				throw new IllegalStateException("문제 상품");
			}
			return requested.stream().map(id -> mock(Product.class)).toList();
		});

		// when
		ProductSearchBackfillResult result = sut.backfill(BATCH_SIZE);

		// then
		assertThat(result).isEqualTo(new ProductSearchBackfillResult(BATCH_SIZE - 1, 1));
	}

	// 첫 조회는 한 묶음을 돌려주고, 그 뒤 조회는 비어 있어 루프가 끝난다
	private void givenOneBatch(List<Long> ids) {
		given(productSearchRepository.findBackfillTargetIds(eq(0L), anyInt(), eq(BATCH_SIZE))).willReturn(ids);
		given(productSearchRepository.findBackfillTargetIds(eq(ids.getLast()), anyInt(), eq(BATCH_SIZE)))
			.willReturn(List.of());
		given(transactionManager.getTransaction(any()))
			.willReturn(mock(TransactionStatus.class));
	}
}
