package com.ok.product.review.adapter.out.persistence;

import com.ok.product.review.application.port.out.ProductReviewRepository;
import com.ok.product.review.domain.ProductReview;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ProductReviewJpaRepository extends JpaRepository<ProductReview, Long>, ProductReviewRepository {

	// JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
	// 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
	@Override
	<S extends ProductReview> S saveAndFlush(S review);

	// 수정·삭제용 조회. 같은 리뷰의 수정·삭제가 동시에 오면 둘 다 같은 이전 별점으로 집계를 바꾸므로 행을 잠가 차례로 처리한다
	@Override
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<ProductReview> findForUpdateByIdAndDeletedAtIsNull(Long id);
}
