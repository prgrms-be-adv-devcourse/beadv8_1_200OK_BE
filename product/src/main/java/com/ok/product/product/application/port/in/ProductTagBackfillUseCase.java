package com.ok.product.product.application.port.in;

import com.ok.product.product.application.dto.ProductTagBackfillResult;

/**
 * 판정을 받아야 하는 상품을 판정 대기열에 채우는 유스케이스. 앱이 뜰 때 부른다.
 */
public interface ProductTagBackfillUseCase {

	/**
	 * 판정 행이 없는 상품, 태그 목록 버전이 지금과 다른 완료 상품, 최종 실패 상품을 대기로 넣는다. 삭제된 상품은 넣지 않는다.
	 * 대기열만 채우고 판정은 작업자가 한다. 이미 대기인 상품은 건드리지 않아 두 번 돌려도 결과가 같다.
	 */
	ProductTagBackfillResult backfill();

}
