package com.ok.product.product.adapter.out.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.ok.product.ProductSchema;
import com.ok.product.product.application.dto.UntaggedProductRow;
import com.ok.product.product.application.port.out.ProductTagJudgmentRepository;
import com.ok.product.product.domain.ProductTagJudgment;
import com.ok.product.product.domain.ProductTagJudgmentStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

// 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
public interface ProductTagJudgmentJpaRepository
	extends JpaRepository<ProductTagJudgment, Long>, ProductTagJudgmentRepository {

	String JUDGMENT_TABLE = ProductSchema.NAME + ".product_tag_judgment";
	String PRODUCT_TABLE = ProductSchema.NAME + ".product";
	String TAG_TABLE = ProductSchema.NAME + ".product_tag";

	// JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
	// 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
	@Override
	<S extends ProductTagJudgment> S save(S judgment);

	@Override
	void delete(ProductTagJudgment judgment);

	// 작업자가 꺼낼 요청. 오래 기다린 요청부터.
	// 다른 작업자가 꺼내는 중인 행은 기다리지 않고 건너뛴다(timeout -2 = SKIP LOCKED). 잠금만으로는 커밋 뒤에 다시 꺼내지므로 꺼낸 요청은 claim으로 미룬다
	@Override
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	List<ProductTagJudgment> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
		ProductTagJudgmentStatus status, LocalDateTime now, Limit limit);

	@Override
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT j FROM ProductTagJudgment j WHERE j.id = :id")
	Optional<ProductTagJudgment> findForUpdateById(@Param("id") Long id);

	// 백필이 같은 상품의 행을 먼저 만들었어도 실패하지 않게 UNIQUE(product_id) 충돌을 갱신으로 받는다
	@Override
	@Modifying
	@Query(value = "INSERT INTO " + JUDGMENT_TABLE + """
		 (product_id, status, attempt_count, next_attempt_at, requested_at, created_at, updated_at)
		VALUES (:productId, 'PENDING', 0, :now, :now, :now, :now)
		ON DUPLICATE KEY UPDATE status = 'PENDING', attempt_count = 0, next_attempt_at = :now, requested_at = :now,
		    last_error = NULL, updated_at = :now
		""", nativeQuery = true)
	void request(@Param("productId") Long productId, @Param("now") LocalDateTime now);

	// 상품 등록이 같은 상품의 행을 먼저 만들었으면 그 행을 그대로 둔다(ON DUPLICATE KEY의 갱신 없는 대입).
	// SELECT에도 같은 칼럼 이름이 있어 대입하는 칼럼을 테이블 이름으로 한정한다
	@Override
	@Modifying
	@Query(value = "INSERT INTO " + JUDGMENT_TABLE + """
		 (product_id, status, attempt_count, next_attempt_at, requested_at, created_at, updated_at)
		SELECT p.id, 'PENDING', 0, :now, :now, :now, :now
		""" + "FROM " + PRODUCT_TABLE + " p LEFT JOIN " + JUDGMENT_TABLE + """
		 j ON j.product_id = p.id
		WHERE p.deleted_at IS NULL
		  AND j.id IS NULL
		""" + "ON DUPLICATE KEY UPDATE " + JUDGMENT_TABLE + ".status = " + JUDGMENT_TABLE + ".status", nativeQuery = true)
	int insertMissing(@Param("now") LocalDateTime now);

	@Override
	@Modifying
	@Query(value = "UPDATE " + JUDGMENT_TABLE + " j JOIN " + PRODUCT_TABLE + """
		 p ON p.id = j.product_id
		SET j.status = 'PENDING', j.attempt_count = 0, j.next_attempt_at = :now, j.requested_at = :now,
		    j.last_error = NULL, j.updated_at = :now
		WHERE p.deleted_at IS NULL
		  AND (j.status = 'FAILED' OR (j.status = 'COMPLETED' AND j.tag_version <> :currentVersion))
		""", nativeQuery = true)
	int requeueStale(@Param("currentVersion") String currentVersion, @Param("now") LocalDateTime now);

	@Override
	@Query(value = "SELECT j.product_id, p.name, j.judged_at, j.top_tag_id, j.top_score FROM " + JUDGMENT_TABLE
		+ " j JOIN " + PRODUCT_TABLE + """
		 p ON p.id = j.product_id
		WHERE j.status = 'COMPLETED'
		  AND j.judged_at >= :from
		  AND j.judged_at < :to
		  AND p.deleted_at IS NULL
		""" + "  AND NOT EXISTS (SELECT 1 FROM " + TAG_TABLE + """
		 t WHERE t.product_id = j.product_id)
		ORDER BY j.judged_at DESC, j.product_id DESC
		LIMIT :limit
		""", nativeQuery = true)
	List<UntaggedProductRow> findUntagged(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
		@Param("limit") int limit);
}
