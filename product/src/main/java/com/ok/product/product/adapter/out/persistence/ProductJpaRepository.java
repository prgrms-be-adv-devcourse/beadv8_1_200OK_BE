package com.ok.product.product.adapter.out.persistence;

import com.ok.product.product.application.port.out.ProductRepository;
import com.ok.product.product.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<Product, Long>, ProductRepository {

	// JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
	// 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
	@Override
	<S extends Product> S save(S product);

	// 수정용 조회. 옵션만 바뀐 수정에서도 커밋 시 version을 무조건 +1 해서 동시 수정을 감지한다
	@Override
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	Optional<Product> findForUpdateByIdAndDeletedAtIsNull(Long id);

	// 리뷰 작성·수정·삭제 시 리뷰 집계 증감.
	// 변경 감지로 저장하면 @Version이 올라가 판매자의 상품 수정이 충돌로 실패하므로 bulk UPDATE로 버전을 건드리지 않는다.
	// 읽어서 더하지 않고 DB에서 더해야 동시에 리뷰가 달려도 숫자가 빠지지 않는다.
	// 영속성 컨텍스트를 거치지 않으므로 같은 트랜잭션에서 읽어 둔 Product의 집계 값은 갱신 전 값이다.
	@Override
	@Modifying
	@Query("""
			UPDATE Product p
			SET p.reviewCount = p.reviewCount + :countDelta,
			    p.ratingSum = p.ratingSum + :ratingDelta
			WHERE p.id = :productId
			""")
	int addReviewStats(Long productId, int countDelta, int ratingDelta);

	// 옵션 매핑을 전역으로 바꾸지 않고 백필 조회에서만 fetch join 한다. 옵션이 없는 상품도 나와야 하므로 left join이다
	@Override
	@Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.options WHERE p.id IN :ids")
	List<Product> findAllWithOptionsByIdIn(@Param("ids") Collection<Long> ids);
}
