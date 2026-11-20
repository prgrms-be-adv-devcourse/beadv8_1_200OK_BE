package com.ok.product.product.adapter.out.persistence;

import com.ok.product.ProductSchema;
import com.ok.product.product.application.port.out.ProductSearchRepository;
import com.ok.product.product.domain.ProductSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProductSearchJpaRepository extends JpaRepository<ProductSearch, Long>, ProductSearchRepository {

	// JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
	// 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
	@Override
	Optional<ProductSearch> findById(Long productId);

	@Override
	<S extends ProductSearch> S save(S productSearch);

	// 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
	@Override
	@Query(value = "SELECT p.id FROM " + ProductSchema.NAME + ".product p LEFT JOIN " + ProductSchema.NAME + """
		.product_search s ON s.product_id = p.id
		WHERE p.deleted_at IS NULL
		  AND p.id > :afterId
		  AND (s.product_id IS NULL OR s.normalizer_version < :currentVersion)
		ORDER BY p.id
		LIMIT :limit
		""", nativeQuery = true)
	List<Long> findBackfillTargetIds(@Param("afterId") long afterId, @Param("currentVersion") int currentVersion,
		@Param("limit") int limit);

	@Override
	@Modifying
	@Query("UPDATE ProductSearch s SET s.keywords = :keywords, s.updatedAt = :now WHERE s.productId = :productId")
	int updateKeywords(@Param("productId") Long productId, @Param("keywords") String keywords,
		@Param("now") LocalDateTime now);
}
