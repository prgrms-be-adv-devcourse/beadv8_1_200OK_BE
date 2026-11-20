package com.ok.product.product.application.port.out;

import com.ok.product.product.application.dto.ProductTagInput;
import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.exception.ProductTagJudgeFailedException;
import com.ok.product.product.exception.ProductTagJudgeUnavailableException;

/**
 * 상품이 태그 목록의 상황·용도에 맞는지 판정한다. 외부 판정 서비스(Jev)를 이 인터페이스 뒤에 둔다.
 * 확률만 돌려주고, 붙일 태그는 {@link ProductTagScores}가 정한다.
 */
public interface ProductTagJudge {

	/**
	 * 태그 목록의 태그마다 "해당" 확률을 판정한다.
	 *
	 * @throws ProductTagJudgeFailedException 판정하지 못하면. 다시 시도할 만한 실패인지 함께 알린다
	 * @throws ProductTagJudgeUnavailableException 판정기가 어떤 요청도 처리할 수 없으면(인증 거절 등)
	 */
	ProductTagScores judge(ProductTagInput input);
}
