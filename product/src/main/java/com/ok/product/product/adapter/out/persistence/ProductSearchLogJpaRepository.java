package com.ok.product.product.adapter.out.persistence;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
