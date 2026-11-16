package com.ok.product.product.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.product.product.application.dto.ProductSearchBackfillResult;
import com.ok.product.product.application.port.in.ProductSearchBackfillUseCase;
import com.ok.product.product.application.port.out.ProductRepository;
import com.ok.product.product.application.port.out.ProductSearchRepository;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.SearchTextNormalizer;

import lombok.extern.slf4j.Slf4j;

/**
 * 검색 행이 없거나 정규화 버전이 낮은 상품의 검색 행을 {@link ProductSearchIndexer}로 다시 계산한다.
 * 서버가 2대가 되면 ShedLock을 건다.
 */
@Slf4j
@Service
public class ProductSearchBackfillService implements ProductSearchBackfillUseCase {

	private static final int BATCH_SIZE = 500;

	private final ProductSearchRepository productSearchRepository;
	private final ProductRepository productRepository;
	private final ProductSearchIndexer productSearchIndexer;
	private final TransactionTemplate batchTransaction;

	public ProductSearchBackfillService(ProductSearchRepository productSearchRepository,
		ProductRepository productRepository, ProductSearchIndexer productSearchIndexer,
		PlatformTransactionManager transactionManager) {
		this.productSearchRepository = productSearchRepository;
		this.productRepository = productRepository;
		this.productSearchIndexer = productSearchIndexer;
		this.batchTransaction = new TransactionTemplate(transactionManager);
		// 부르는 쪽 트랜잭션에 참여하면 한 묶음의 실패가 바깥 트랜잭션을 롤백 전용으로 만들어 다음 묶음까지 버려진다
		this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public ProductSearchBackfillResult backfill() {
		return backfill(BATCH_SIZE);
	}

	ProductSearchBackfillResult backfill(int batchSize) {
		int processedCount = 0;
		int failedCount = 0;
		long afterId = 0;
		List<Long> ids = findTargetIds(afterId, batchSize);
		while (!ids.isEmpty()) {
			try {
				processedCount += indexInNewTransaction(ids);
			} catch (RuntimeException e) {
				failedCount += ids.size();
				log.error("검색 행 백필 묶음 실패. firstProductId={}, lastProductId={}", ids.getFirst(), ids.getLast(), e);
			}
			// 처음부터 다시 찾지 않고 직전 묶음 뒤부터 읽는다. 다시 찾으면 계속 실패하는 상품이 같은 묶음에 남아 끝없이 반복한다
			afterId = ids.getLast();
			ids = findTargetIds(afterId, batchSize);
		}

		if (processedCount + failedCount > 0) {
			log.info("검색 행 백필 종료. processedCount={}, failedCount={}", processedCount, failedCount);
		}
		return new ProductSearchBackfillResult(processedCount, failedCount);
	}

	private List<Long> findTargetIds(long afterId, int batchSize) {
		return productSearchRepository.findBackfillTargetIds(afterId, SearchTextNormalizer.VERSION, batchSize);
	}

	private int indexInNewTransaction(List<Long> ids) {
		return batchTransaction.execute(status -> {
			List<Product> products = productRepository.findAllById(ids);
			products.forEach(productSearchIndexer::index);
			return products.size();
		});
	}
}
