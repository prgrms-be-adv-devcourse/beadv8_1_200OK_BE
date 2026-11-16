package com.ok.product.product.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.application.dto.StockPartialReleaseCommand;

/**
 * 환불 한 건만큼 주문의 옵션 배정 재고를 일부 되돌리는 유스케이스.
 */
public interface StockPartialReleaseUseCase {

	/**
	 * 환불 한 건만큼 주문의 옵션 배정 재고를 일부 되돌린다.
	 * 출고 전 취소 전용이다. 같은 환불 ID로 같은 내용이 이미 반영됐으면 성공으로 끝낸다.
	 *
	 * @throws RestApiException 같은 환불 ID로 내용이 다르면 {@code PARTIAL_RELEASE_MISMATCH},
	 *                          주문에 그 옵션의 배정 기록이 없으면 {@code ALLOCATION_NOT_FOUND},
	 *                          해제 가능 수량보다 많거나 이미 전체 해제한 주문이면 {@code RELEASE_QUANTITY_EXCEEDED}
	 */
	void partialRelease(StockPartialReleaseCommand command);

}
