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
 */
// TODO(#704): 서버가 2대가 되면 ShedLock을 건다. 현재는 두 인스턴스가 같은 상품을 INSERT하면 PK 중복으로 묶음이 실패해 건별 재시도까지 거치고 ERROR 로그가 남는다
@Slf4j
@Service
public class ProductSearchBackfillService implements ProductSearchBackfillUseCase {

	private static final int BATCH_SIZE = 500;
	// DB 장애면 모든 건이 실패하므로 끝까지 재시도해 봐야 로그와 대기 시간만 배가 된다
	private static final int MAX_CONSECUTIVE_FAILURES = 10;

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
		log.info("검색 행 백필 시작");
		int processedCount = 0;
		int failedCount = 0;
		long afterId = 0;
		List<Long> ids = findTargetIds(afterId, batchSize);
		while (!ids.isEmpty()) {
			try {
				processedCount += indexInNewTransaction(ids);
			} catch (RuntimeException e) {
				// 한 건 때문에 묶음 전체를 버리지 않도록 건별로 다시 시도해 문제 상품만 건너뛴다
				log.warn("검색 행 백필 묶음 실패. 건별로 다시 시도한다. firstProductId={}, lastProductId={}", ids.getFirst(),
					ids.getLast(), e);
				int consecutiveFailures = 0;
				for (int i = 0; i < ids.size(); i++) {
					try {
						processedCount += indexInNewTransaction(List.of(ids.get(i)));
						consecutiveFailures = 0;
					} catch (RuntimeException single) {
						failedCount++;
						consecutiveFailures++;
						if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
							int skippedCount = ids.size() - i - 1;
							failedCount += skippedCount;
							log.error("검색 행 백필 연속 실패로 묶음의 남은 건별 재시도를 건너뛴다. firstProductId={}, lastProductId={}, consecutiveFailures={}, skippedCount={}",
								ids.getFirst(), ids.getLast(), consecutiveFailures, skippedCount, single);
							break;
						}
						log.error("검색 행 백필 실패. productId={}", ids.get(i), single);
					}
				}
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
			List<Product> products = productRepository.findAllWithOptionsByIdIn(ids);
			productSearchIndexer.indexAll(products);
			return products.size();
		});
	}
}
