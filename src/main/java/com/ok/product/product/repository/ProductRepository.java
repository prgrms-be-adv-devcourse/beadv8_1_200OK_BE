package com.ok.product.product.repository;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductVisibility;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

	// 활성 상태 상품 조회
	Optional<Product> findByIdAndDeletedAtIsNull(Long id);

	// 구매자 상세 조회
	Optional<Product> findByIdAndVisibilityAndDeletedAtIsNull(Long id, ProductVisibility visibility);

	// 수정용 조회. 옵션만 바뀐 수정에서도 커밋 시 version을 무조건 +1 해서 동시 수정을 감지한다
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	Optional<Product> findForUpdateByIdAndDeletedAtIsNull(Long id);
}
