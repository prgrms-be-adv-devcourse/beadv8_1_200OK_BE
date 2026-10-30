package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.ShippingFeeQuoteQuery;
import com.ok.delivery.application.dto.ShippingFeeQuoteResult;

/**
 * 배송비와 예정일 견적 유스케이스.
 */
public interface ShippingFeeQuoteUseCase {

    /**
     * 배송비와 예정일을 견적한다. 결제 시각을 알 수 없으므로 요청 시각을 기준으로 출고 예정일을 계산한다.
     * 지역 판정과 판매자 설정 조회는 판매자마다 하지 않고 한 번씩만 한다.
     */
    ShippingFeeQuoteResult quote(ShippingFeeQuoteQuery query);
}
