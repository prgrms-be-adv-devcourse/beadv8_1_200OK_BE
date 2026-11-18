package com.ok.product.product.application.port.out;

import com.ok.product.product.domain.ProductSearchLog;

/**
 * 검색어 로그를 검색 응답과 떼어 남기는 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * 검색 응답이 저장을 기다리지 않아야 하므로, 구현은 저장을 끝내지 않고 돌아오며 실패하거나 밀리면 로그를 버린다.
 * 호출하는 쪽으로 예외를 던지지 않는다.
 */
public interface ProductSearchLogRecorder {

	void record(ProductSearchLog log);

}
