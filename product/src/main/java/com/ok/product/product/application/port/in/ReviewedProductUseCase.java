package com.ok.product.product.application.port.in;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ReviewedProductResult;

/**
 * 리뷰 하위 도메인이 리뷰 대상 상품을 확인하고 리뷰 집계를 반영하는 유스케이스.
 * 리뷰는 상품 엔티티와 저장소 대신 이 유스케이스로만 상품을 본다. 리뷰 서비스의 트랜잭션 안에서 불려 그 트랜잭션에 참여한다.
 */
public interface ReviewedProductUseCase {

	/**
	 * 삭제되지 않은 상품을 조회한다. 숨긴 상품도 돌려준다.
	 *
	 * @throws RestApiException 상품이 없거나 삭제됐으면 {@code PRODUCT_NOT_FOUND}
	 */
	ReviewedProductResult getActiveProduct(Long productId);

	/**
	 * 구매자에게 보이는(공개, 삭제 안 됨) 상품의 분류를 조회한다.
	 *
	 * @throws RestApiException 상품이 없거나 숨겨졌거나 삭제됐으면 {@code PRODUCT_NOT_FOUND}
	 */
	ProductCategoryCode getVisibleProductCategory(Long productId);

	/**
	 * 판매자 본인의 삭제되지 않은 상품인지 확인한다.
	 *
	 * @throws RestApiException 상품이 없거나 삭제됐거나 다른 판매자의 상품이면 {@code PRODUCT_NOT_FOUND}
	 */
	void checkSellerProduct(Long sellerId, Long productId);

	/**
	 * 상품 ID별 이름과 분류를 조회한다. 없는 ID는 빠지고, 숨기거나 삭제한 상품도 포함한다.
	 */
	Map<Long, ReviewedProductResult> findProducts(Collection<Long> productIds);

	/**
	 * 옵션 ID별 옵션 이름을 조회한다. 없는 ID는 빠지고, 삭제한 옵션도 포함한다.
	 */
	Map<Long, String> findOptionNames(Collection<Long> optionIds);

	/**
	 * 옵션이 그 상품의 것이면 옵션 이름을 돌려준다. 삭제한 옵션도 포함한다.
	 */
	Optional<String> findOptionName(Long productId, Long optionId);

	/**
	 * 리뷰 수와 별점 합계를 DB에서 더한다. 상품 행에 배타 락을 잡는다.
	 * 영속성 컨텍스트를 거치지 않으므로 같은 트랜잭션에서 읽어 둔 상품의 집계 값은 갱신 전 값이다.
	 * 리뷰를 저장하는 트랜잭션 안에서만 부른다. 트랜잭션 없이 부르면 {@code IllegalTransactionStateException}이 난다.
	 */
	void addReviewStats(Long productId, int countDelta, int ratingDelta);

}
