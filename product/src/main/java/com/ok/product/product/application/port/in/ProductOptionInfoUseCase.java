package com.ok.product.product.application.port.in;

import java.util.List;

import com.ok.product.product.application.dto.ProductOptionInfoResult;

/**
 * 다른 모듈(주문)이 옵션 정보를 조회하는 유스케이스.
 */
public interface ProductOptionInfoUseCase {

	/**
	 * 옵션 ID 목록으로 옵션 정보를 한 번에 조회한다.
	 * 없는 ID는 빠지고, 숨기거나 삭제한 상품 및 옵션도 포함한다.
	 */
	List<ProductOptionInfoResult> findOptionInfos(List<Long> optionIds);

}
