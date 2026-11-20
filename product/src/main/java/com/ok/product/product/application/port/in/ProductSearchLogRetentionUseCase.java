package com.ok.product.product.application.port.in;

/**
 * 보존 기간이 지난 검색어 로그를 지우는 유스케이스. 작업자(스케줄러)가 부른다.
 */
public interface ProductSearchLogRetentionUseCase {

	/**
	 * 보존 기간이 지난 검색어 로그를 지운다. 여러 번 불러도 결과가 같다.
	 */
	void deleteExpiredLogs();

}
