package com.ok.product.product.repository;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductVisibility;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

	// 활성 상태 상품 조회
	Optional<Product> findByIdAndDeletedAtIsNull(Long id);

	// 구매자 상세 조회
	Optional<Product> findByIdAndVisibilityAndDeletedAtIsNull(Long id, ProductVisibility visibility);

	// 수정용 조회. 옵션만 바뀐 수정에서도 커밋 시 version을 무조건 +1 해서 동시 수정을 감지한다
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	Optional<Product> findForUpdateByIdAndDeletedAtIsNull(Long id);

	// 리뷰 작성·수정·삭제 시 리뷰 집계 증감.
	// 변경 감지로 저장하면 @Version이 올라가 판매자의 상품 수정이 충돌로 실패하므로 bulk UPDATE로 버전을 건드리지 않는다.
	// 읽어서 더하지 않고 DB에서 더해야 동시에 리뷰가 달려도 숫자가 빠지지 않는다.
	// 영속성 컨텍스트를 거치지 않으므로 같은 트랜잭션에서 읽어 둔 Product의 집계 값은 갱신 전 값이다.
	@Modifying
	@Query("""
			UPDATE Product p
			SET p.reviewCount = p.reviewCount + :countDelta,
			    p.ratingSum = p.ratingSum + :ratingDelta
			WHERE p.id = :productId
			""")
	int addReviewStats(Long productId, int countDelta, int ratingDelta);
}
