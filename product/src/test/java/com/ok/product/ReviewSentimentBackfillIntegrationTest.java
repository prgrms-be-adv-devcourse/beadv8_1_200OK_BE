package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ReviewAnalysisRequest;
import com.ok.product.review.domain.ReviewAnalysisStatus;
import com.ok.product.review.domain.ReviewAnalysisType;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.repository.ReviewAnalysisRequestRepository;
import com.ok.testsupport.AbstractIntegrationTest;

// 감성 분석 전에 쓰인 리뷰를 대기 요청으로 넣는 마이그레이션 파일을, 리뷰가 있는 DB에 그대로 다시 실행해 확인한다
class ReviewSentimentBackfillIntegrationTest extends AbstractIntegrationTest {

	private static final String BACKFILL_MIGRATION =
		"db/migration/product/V202611041146__product__insert_pending_review_sentiment_requests.sql";

	@Autowired
	DataSource dataSource;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductReviewRepository productReviewRepository;

	@Autowired
	ReviewAnalysisRequestRepository analysisRequestRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@BeforeEach
	void setUp() {
		clearReviews();
	}

	// 다른 상품 통합 테스트가 상품을 지울 때 리뷰의 외래 키에 걸리지 않게 리뷰를 남기지 않는다
	@AfterEach
	void tearDown() {
		clearReviews();
	}

	@Test
	void 마이그레이션_뒤_삭제되지_않은_기존_리뷰마다_대기_요청이_하나씩_있다() {
		// given: 감성 분석 전에 쓰여 분석 요청이 없는 리뷰 2건과 삭제된 리뷰 1건
		Long productId = productRepository.save(Product.create(10L, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10)),
			List.of("product/a.jpg"))).getId();
		Long optionId = transactionTemplate.execute(
			status -> productRepository.findById(productId).orElseThrow().getOptions().getFirst().getId());
		Long first = productReviewRepository.save(review(productId, optionId, 100L)).getId();
		Long second = productReviewRepository.save(review(productId, optionId, 101L)).getId();
		ProductReview deleted = review(productId, optionId, 102L);
		deleted.delete(LocalDateTime.now());
		productReviewRepository.save(deleted);

		// when
		new ResourceDatabasePopulator(new ClassPathResource(BACKFILL_MIGRATION)).execute(dataSource);

		// then
		List<ReviewAnalysisRequest> sut = analysisRequestRepository.findAll();
		assertThat(sut).extracting(ReviewAnalysisRequest::getReviewId).containsExactlyInAnyOrder(first, second);
		assertSoftly(softly -> sut.forEach(request -> {
			softly.assertThat(request.getAnalysisType()).isEqualTo(ReviewAnalysisType.SENTIMENT);
			softly.assertThat(request.getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
			softly.assertThat(request.getAttemptCount()).isZero();
		}));
	}

	private ProductReview review(Long productId, Long optionId, long orderId) {
		return ProductReview.create(productId, optionId, orderId, orderId * 10, 1L, 9, "달고 맛있어요", List.of());
	}

	private void clearReviews() {
		analysisRequestRepository.deleteAll();
		productReviewRepository.deleteAll();
	}
}
