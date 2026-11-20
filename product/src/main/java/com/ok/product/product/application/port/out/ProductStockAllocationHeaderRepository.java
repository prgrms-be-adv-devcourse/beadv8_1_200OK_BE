package com.ok.product.product.application.port.out;

import java.time.LocalDateTime;

import com.ok.product.product.domain.StockAllocationStatus;

/**
 * 주문 단위 재고 배정 헤더 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * 두 upsert는 같은 주문의 배정·해제를 줄 세우는 헤더 행 X 잠금을 잡는다. 트랜잭션에서 이 포트의 다른 조회보다 먼저 불러야 한다.
 */
public interface ProductStockAllocationHeaderRepository {

	// 없으면 ALLOCATED로 만들고, 있으면 상태를 바꾸지 않고 잠금만 잡는다
	void insertAllocatedIfAbsent(Long orderId, LocalDateTime now);

	// 없어도 RELEASED로 만든다. 해제가 배정보다 먼저 와도 이 기록이 뒤늦은 배정을 막는다
	void upsertReleased(Long orderId, LocalDateTime now);

	// upsert로 잡은 잠금 아래에서 읽는다
	StockAllocationStatus findStatusForUpdate(Long orderId);

}
