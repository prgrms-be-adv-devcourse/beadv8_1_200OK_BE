package com.ok.product.review.repository;

import com.ok.product.review.domain.ProductReview;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

	// 수정·삭제용 조회. 같은 리뷰의 수정·삭제가 동시에 오면 둘 다 같은 이전 별점으로 집계를 바꾸므로 행을 잠가 차례로 처리한다
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<ProductReview> findForUpdateByIdAndDeletedAtIsNull(Long id);

	// 한 주문에서 같은 상품은 리뷰 1개. 삭제한 리뷰도 포함해서 본다 (A11)
	boolean existsByOrderIdAndProductId(Long orderId, Long productId);

	// 상품별 리뷰 목록
	Page<ProductReview> findByProductIdAndDeletedAtIsNullOrderByIdDesc(Long productId, Pageable pageable);

	// 내가 쓴 리뷰 목록
	Page<ProductReview> findByMemberIdAndDeletedAtIsNullOrderByIdDesc(Long memberId, Pageable pageable);

	// 상품 상세 상단에 띄울 이 상품의 내 리뷰. 재구매하면 주문마다 쓸 수 있어 여러 건일 수 있다
	List<ProductReview> findByProductIdAndMemberIdAndDeletedAtIsNullOrderByIdDesc(Long productId, Long memberId);
}
