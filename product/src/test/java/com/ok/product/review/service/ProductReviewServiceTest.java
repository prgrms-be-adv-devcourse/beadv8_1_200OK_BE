package com.ok.product.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ProductReviewMediaSpec;
import com.ok.product.review.domain.ProductReviewMediaType;
import com.ok.product.review.exception.ProductReviewErrorCode;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.service.dto.ProductReviewCreateCommand;
import com.ok.product.review.service.dto.ProductReviewResult;
import com.ok.product.review.service.dto.ProductReviewUpdateCommand;
import com.ok.product.review.service.dto.ReviewablePurchase;

@ExtendWith(MockitoExtension.class)
class ProductReviewServiceTest {

	private static final String BASE_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/";
	private static final Long MEMBER_ID = 1L;
	private static final Long OTHER_MEMBER_ID = 2L;
	private static final Long PRODUCT_ID = 10L;
	private static final Long OPTION_ID = 100L;
	private static final Long ORDER_ID = 1000L;
	private static final Long ORDER_ITEM_ID = 10000L;
	private static final Long REVIEW_ID = 5L;

	@Mock
	ProductReviewRepository productReviewRepository;

	@Mock
	ProductRepository productRepository;

	@Mock
	ProductOptionRepository productOptionRepository;

	ProductReviewService sut;

	@BeforeEach
	void setUp() {
		MediaUrlResolver mediaUrlResolver = new MediaUrlResolver("bucket", "ap-northeast-2");
		Clock clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);
		sut = new ProductReviewService(productReviewRepository, productRepository, productOptionRepository,
			new ProductReviewMediaValidator(mediaUrlResolver), mediaUrlResolver, clock);
	}

	@Test
	void 작성하면_리뷰_개수_1과_별점이_집계에_더해진다() {
		// given
		givenProduct();
		given(productReviewRepository.saveAndFlush(any())).willAnswer(invocation -> {
			ProductReview review = invocation.getArgument(0);
			ReflectionTestUtils.setField(review, "id", REVIEW_ID);
			return review;
		});

		// when
		Long reviewId = sut.create(MEMBER_ID, PRODUCT_ID, purchase(), createCommand(4));

		// then
		assertThat(reviewId).isEqualTo(REVIEW_ID);
		then(productRepository).should().addReviewStats(PRODUCT_ID, 1, 4);
	}

	@Test
	void 작성하면_주문_모듈이_확인해_준_구매_건의_주문_옵션_ID로_저장한다() {
		// given
		givenProduct();
		given(productReviewRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

		// when
		sut.create(MEMBER_ID, PRODUCT_ID, purchase(), createCommand(4));

		// then
		ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
		then(productReviewRepository).should().saveAndFlush(captor.capture());
		ProductReview saved = captor.getValue();
		assertThat(saved).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
			softly.assertThat(saved.getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
			softly.assertThat(saved.getProductOptionId()).isEqualTo(OPTION_ID);
			softly.assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
			softly.assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
		});
	}

	@Test
	void 삭제된_상품에는_작성할_수_없다() {
		// given
		given(productRepository.findByIdAndDeletedAtIsNull(PRODUCT_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> sut.create(MEMBER_ID, PRODUCT_ID, purchase(), createCommand(4)))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
		then(productRepository).should(never()).addReviewStats(anyLong(), anyInt(), anyInt());
	}

	@Test
	void 같은_주문의_같은_상품은_다시_작성할_수_없다() {
		// given
		givenProduct();
		given(productReviewRepository.existsByOrderIdAndProductId(ORDER_ID, PRODUCT_ID)).willReturn(true);

		// when & then
		assertThatThrownBy(() -> sut.create(MEMBER_ID, PRODUCT_ID, purchase(), createCommand(4)))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.DUPLICATE_REVIEW));
		then(productRepository).should(never()).addReviewStats(anyLong(), anyInt(), anyInt());
	}

	@Test
	void 동시에_작성해_UNIQUE_위반이_나면_중복_리뷰로_바꾼다() {
		// given
		givenProduct();
		given(productReviewRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk"));

		// when & then
		assertThatThrownBy(() -> sut.create(MEMBER_ID, PRODUCT_ID, purchase(), createCommand(4)))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.DUPLICATE_REVIEW));
	}

	@Test
	void 별점을_3에서_5로_바꾸면_합계만_2_늘어난다() {
		// given
		givenReview(MEMBER_ID, 3);

		// when
		sut.update(MEMBER_ID, REVIEW_ID, new ProductReviewUpdateCommand(5, "더 좋아요", List.of()));

		// then
		then(productRepository).should().addReviewStats(PRODUCT_ID, 0, 2);
	}

	@Test
	void 남의_리뷰는_수정할_수_없다() {
		// given
		givenReview(OTHER_MEMBER_ID, 3);

		// when & then
		assertThatThrownBy(() -> sut.update(MEMBER_ID, REVIEW_ID, new ProductReviewUpdateCommand(5, "좋아요", List.of())))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.REVIEW_ACCESS_DENIED));
	}

	@Test
	void 삭제하면_리뷰_개수_1과_별점이_집계에서_빠진다() {
		// given
		ProductReview review = givenReview(MEMBER_ID, 4);

		// when
		sut.delete(MEMBER_ID, REVIEW_ID);

		// then
		assertThat(review.isDeleted()).isTrue();
		then(productRepository).should().addReviewStats(PRODUCT_ID, -1, -4);
	}

	@Test
	void 이미_삭제한_리뷰는_다시_삭제할_수_없다() {
		// given
		// 삭제한 리뷰는 조회 조건(deleted_at IS NULL)에서 빠진다
		given(productReviewRepository.findForUpdateByIdAndDeletedAtIsNull(REVIEW_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> sut.delete(MEMBER_ID, REVIEW_ID))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.REVIEW_NOT_FOUND));
		then(productRepository).should(never()).addReviewStats(anyLong(), anyInt(), anyInt());
	}

	@Test
	void 남의_리뷰는_삭제할_수_없다() {
		// given
		givenReview(OTHER_MEMBER_ID, 4);

		// when & then
		assertThatThrownBy(() -> sut.delete(MEMBER_ID, REVIEW_ID))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.REVIEW_ACCESS_DENIED));
	}

	@Test
	void 숨긴_상품의_리뷰_목록은_조회할_수_없다() {
		// given
		given(productRepository.findByIdAndVisibilityAndDeletedAtIsNull(PRODUCT_ID, ProductVisibility.VISIBLE))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> sut.getProductReviews(PRODUCT_ID, PageRequest.of(0, 10)))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
	}

	@Test
	void 리뷰_목록에_구매_옵션_이름과_첨부_URL을_담는다() {
		// given
		Pageable pageable = PageRequest.of(0, 10);
		ProductReview review = review(MEMBER_ID, 5, List.of(
			new ProductReviewMediaSpec("product_review/a.jpg", ProductReviewMediaType.IMAGE)));
		ProductOption option = mock(ProductOption.class);
		given(option.getId()).willReturn(OPTION_ID);
		given(option.getName()).willReturn("1kg");
		given(productReviewRepository.findByMemberIdAndDeletedAtIsNullOrderByIdDesc(MEMBER_ID, pageable))
			.willReturn(new PageImpl<>(List.of(review), pageable, 1));
		given(productOptionRepository.findAllById(List.of(OPTION_ID))).willReturn(List.of(option));

		// when
		ProductReviewResult result = sut.getMyReviews(MEMBER_ID, pageable).getContent().getFirst();

		// then
		assertThat(result).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(result.optionName()).isEqualTo("1kg");
			softly.assertThat(result.media()).hasSize(1);
			softly.assertThat(result.media().getFirst().url()).isEqualTo(BASE_URL + "product_review/a.jpg");
			softly.assertThat(result.media().getFirst().type()).isEqualTo("IMAGE");
		});
	}

	private void givenProduct() {
		given(productRepository.findByIdAndDeletedAtIsNull(PRODUCT_ID)).willReturn(Optional.of(mock(Product.class)));
	}

	private ReviewablePurchase purchase() {
		return new ReviewablePurchase(ORDER_ID, ORDER_ITEM_ID, OPTION_ID);
	}

	private ProductReview givenReview(Long memberId, int rating) {
		ProductReview review = review(memberId, rating, List.of());
		given(productReviewRepository.findForUpdateByIdAndDeletedAtIsNull(REVIEW_ID)).willReturn(Optional.of(review));
		return review;
	}

	private ProductReview review(Long memberId, int rating, List<ProductReviewMediaSpec> mediaSpecs) {
		return ProductReview.create(PRODUCT_ID, OPTION_ID, ORDER_ID, ORDER_ITEM_ID, memberId, rating, "맛있어요", mediaSpecs);
	}

	private ProductReviewCreateCommand createCommand(int rating) {
		return new ProductReviewCreateCommand(ORDER_ITEM_ID, rating, "맛있어요", List.of(BASE_URL + "product_review/a.jpg"));
	}
}
