package com.ok.product.product.application.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.application.dto.ZeroResultKeywordQuery;
import com.ok.product.product.application.dto.ZeroResultKeywordResult;
import com.ok.product.product.application.port.in.ProductSearchLogRetentionUseCase;
import com.ok.product.product.application.port.in.ZeroResultKeywordUseCase;
import com.ok.product.product.application.port.out.ProductSearchLogRepository;
import com.ok.product.product.exception.ProductErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 검색어 로그의 보존 기간({@code product.search.log.retention})을 지키고, 로그로 0건 검색어를 집계한다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ProductSearchLogService implements ProductSearchLogRetentionUseCase, ZeroResultKeywordUseCase {

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
		LocalDateTime cutoff = oldestRetainedDate().atStartOfDay();
		int deleted = productSearchLogRepository.deleteBySearchedAtBefore(cutoff);
		log.info("보존 기간이 지난 검색어 로그를 지웠습니다. cutoff={}, deleted={}", cutoff, deleted);
	}

	// 보존 기간 밖의 날을 받으면 이미 지운 날이 0건으로 보여 실제보다 적게 센 결과가 나가므로 거절한다
	@Override
	public List<ZeroResultKeywordResult> getZeroResultKeywords(ZeroResultKeywordQuery query) {
		if (query.from().isAfter(query.to())) {
			throw new RestApiException(ProductErrorCode.INVALID_SEARCH_LOG_PERIOD);
		}
		LocalDate oldest = oldestRetainedDate();
		if (query.from().isBefore(oldest)) {
			throw new RestApiException(ProductErrorCode.SEARCH_LOG_PERIOD_BEYOND_RETENTION,
				"시작일은 " + oldest + " 이후여야 합니다.");
		}
		return productSearchLogRepository.findZeroResultKeywords(
			query.from().atStartOfDay(), query.to().plusDays(1).atStartOfDay(), query.limit());
	}

	private LocalDate oldestRetainedDate() {
		return LocalDate.now(clock).minus(retention);
	}
}
