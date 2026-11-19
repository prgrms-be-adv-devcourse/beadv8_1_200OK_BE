package com.ok.product.product.application.port.in;

/**
 * 쌓인 상품 태그 판정 요청을 처리하는 유스케이스. 작업자(스케줄러)가 부른다.
 */
public interface ProductTagJudgmentUseCase {

	/**
	 * 다음 시도 시각이 지난 대기 요청을 최대 20건 판정한다. 스케줄러와 통합 테스트가 이 메서드를 부른다.
	 * 판정기를 쓸 수 없어 쉬는 중이면 아무것도 꺼내지 않는다.
	 */
	void judgePending();

}
