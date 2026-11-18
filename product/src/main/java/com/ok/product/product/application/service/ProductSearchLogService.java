package com.ok.product.product.application.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ok.product.product.application.port.in.ProductSearchLogRetentionUseCase;
import com.ok.product.product.application.port.out.ProductSearchLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 검색어 로그의 보존 기간({@code product.search.log.retention})을 지킨다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ProductSearchLogService implements ProductSearchLogRetentionUseCase {

	private final ProductSearchLogRepository productSearchLogRepository;
	private final Period retention;
	private final Clock clock;

	ProductSearchLogService(ProductSearchLogRepository productSearchLogRepository,
		@Value("${product.search.log.retention}") Period retention, Clock clock) {
		if (retention.isZero() || retention.isNegative()) {
			throw new IllegalArgumentException("product.search.log.retention은 0보다 커야 합니다.");
		}
		this.productSearchLogRepository = productSearchLogRepository;
		this.retention = retention;
		this.clock = clock;
	}

	// 날짜 단위로 자른다. 0건 검색어 조회가 기간을 날짜로 받으므로, 보존 기간 마지막 날의 로그가 시각에 따라 일부만 남지 않게 한다
	@Override
	@Transactional
	public void deleteExpiredLogs() {
		LocalDateTime cutoff = LocalDate.now(clock).minus(retention).atStartOfDay();
		int deleted = productSearchLogRepository.deleteBySearchedAtBefore(cutoff);
		log.info("보존 기간이 지난 검색어 로그를 지웠습니다. cutoff={}, deleted={}", cutoff, deleted);
	}
}
