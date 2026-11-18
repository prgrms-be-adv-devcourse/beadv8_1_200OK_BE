package com.ok.product.product.adapter.out.persistence;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ok.product.ProductSchema;
import com.ok.product.product.application.dto.ZeroResultKeywordResult;
import com.ok.product.product.application.port.out.ProductSearchLogRepository;
import com.ok.product.product.domain.ProductSearchLog;

public interface ProductSearchLogJpaRepository
	extends JpaRepository<ProductSearchLog, Long>, ProductSearchLogRepository {

	// 보존 기간이 지난 로그는 남길 이유가 없어 soft delete가 아니라 행을 지운다.
	// 파생 delete는 행마다 읽고 지우므로 한 문장으로 지운다
	@Override
	@Modifying
	@Query("delete from ProductSearchLog l where l.searchedAt < :cutoff")
	int deleteBySearchedAtBefore(@Param("cutoff") LocalDateTime cutoff);

	// 대표 원문(묶음에서 가장 최근 행의 원문)을 횟수·마지막 시각과 한 번에 얻으려고 창 함수를 쓴다. GROUP BY만으로는 원문을 고를 수 없다.
	// 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
	@Override
	@Query(value = """
		SELECT t.normalized_keyword, t.zero_result_count, t.searched_at, t.keyword
		FROM (
		    SELECT l.normalized_keyword, l.keyword, l.searched_at,
		           COUNT(*) OVER (PARTITION BY l.normalized_keyword) AS zero_result_count,
		           ROW_NUMBER() OVER (PARTITION BY l.normalized_keyword ORDER BY l.searched_at DESC, l.id DESC) AS row_num
		""" + "    FROM " + ProductSchema.NAME + """
		.product_search_log l
		    WHERE l.result_count = 0
		      AND l.searched_at >= :from
		      AND l.searched_at < :to
		) t
		WHERE t.row_num = 1
		ORDER BY t.zero_result_count DESC, t.searched_at DESC, t.normalized_keyword
		LIMIT :limit
		""", nativeQuery = true)
	List<ZeroResultKeywordResult> findZeroResultKeywords(@Param("from") LocalDateTime from,
		@Param("to") LocalDateTime to, @Param("limit") int limit);
}
