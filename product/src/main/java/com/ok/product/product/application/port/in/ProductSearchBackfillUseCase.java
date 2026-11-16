package com.ok.product.product.application.port.in;

import com.ok.product.product.application.dto.ProductSearchBackfillResult;

/**
 * 검색 행이 비었거나 낡은 상품의 검색 행을 채우는 유스케이스. 앱이 뜰 때 부른다.
 */
public interface ProductSearchBackfillUseCase {

	/**
	 * 검색 행이 없거나 정규화 버전이 지금보다 낮은 미삭제 상품의 검색 행을 다시 계산한다.
	 * 실패한 상품은 행이 그대로 남아 다음 실행 때 다시 대상이 된다.
	 */
	ProductSearchBackfillResult backfill();

}
