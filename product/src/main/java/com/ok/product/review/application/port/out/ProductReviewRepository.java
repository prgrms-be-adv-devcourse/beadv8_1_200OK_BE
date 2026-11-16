package com.ok.product.review.application.port.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.ok.product.review.domain.ProductReview;

/**
 * 리뷰 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * Spring Data에서 물려받던 메서드도 서비스가 쓰는 것만 같은 시그니처로 둔다. 구현체가 Spring Data 프록시라 동작은 그대로다.
 */
public interface ProductReviewRepository {

	/**
	 * 수정·삭제용으로 리뷰 행을 잠가 조회한다. 같은 리뷰의 수정·삭제가 동시에 오면 차례로 처리된다.
	 */
	Optional<ProductReview> findForUpdateByIdAndDeletedAtIsNull(Long id);

	// 한 주문에서 같은 상품은 리뷰 1개. 삭제한 리뷰도 포함해서 본다 (A11)
	boolean existsByOrderIdAndProductId(Long orderId, Long productId);

	// 상품별 리뷰 목록
	Page<ProductReview> findByProductIdAndDeletedAtIsNullOrderByIdDesc(Long productId, Pageable pageable);

	// 내가 쓴 리뷰 목록
	Page<ProductReview> findByMemberIdAndDeletedAtIsNullOrderByIdDesc(Long memberId, Pageable pageable);

	// 상품 상세 상단에 띄울 이 상품의 내 리뷰. 재구매하면 주문마다 쓸 수 있어 여러 건일 수 있다
	List<ProductReview> findByProductIdAndMemberIdAndDeletedAtIsNullOrderByIdDesc(Long productId, Long memberId);

	List<ProductReview> findAllById(Iterable<Long> ids);

	<S extends ProductReview> S saveAndFlush(S review);

}
