package com.ok.product.product.application.port.in;

import java.util.List;

import com.ok.product.product.application.dto.UntaggedProductQuery;
import com.ok.product.product.application.dto.UntaggedProductResult;

/**
 * 판정을 마쳤지만 태그가 하나도 붙지 않은 상품을 보는 관리자 유스케이스. 태그 목록에 빠진 상황이 있는지 찾는 데 쓴다.
 */
public interface UntaggedProductUseCase {

	/**
	 * 기간 안에 판정을 마친 삭제되지 않은 상품 중 태그가 0개인 상품을 최근 판정 순으로 돌려준다. 대기·최종 실패 상품은 넣지 않는다.
	 *
	 * @throws com.ok.common.exception.RestApiException 시작일이 종료일보다 늦으면 {@code INVALID_TAG_JUDGMENT_PERIOD}
	 */
	List<UntaggedProductResult> getUntaggedProducts(UntaggedProductQuery query);

}
