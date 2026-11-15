package com.ok.product.product.application.port.in;

import java.util.List;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.application.dto.StockChange;

/**
 * 주문 단위로 옵션 재고를 배정·해제하는 유스케이스.
 */
public interface StockAllocationUseCase {

	/**
	 * 주문의 옵션별 수량만큼 재고를 배정한다.
	 * 같은 주문으로 다시 호출하면 아무것도 하지 않는다. 하나라도 실패하면 이 주문의 배정은 헤더까지 모두 롤백된다.
	 * 해제를 먼저 받은 주문은 배정 기록이 없어도 거절한다.
	 *
	 * @throws RestApiException 옵션이 없으면 {@code OPTION_NOT_FOUND}, 판매 중이 아니면 {@code OPTION_NOT_PURCHASABLE},
	 *                          판매 가능 수량이 모자라면 {@code INSUFFICIENT_STOCK},
	 *                          이미 해제한 주문이면 {@code ALLOCATION_ALREADY_RELEASED}
	 */
	void allocate(Long orderId, List<StockChange> changes);

	/**
	 * 주문에 배정한 재고를 모두 되돌리고, 그 주문의 이후 배정을 막는다.
	 * 배정 기록이 없어도 해제했다는 기록을 남긴다. 이미 해제했으면 되돌릴 재고가 없다. 그사이 삭제된 옵션도 해제한다.
	 */
	void release(Long orderId);

}
