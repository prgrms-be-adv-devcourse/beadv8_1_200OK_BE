package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.ShippingFeeQuoteQuery;
import com.ok.delivery.application.dto.ShippingFeesResult;

/**
 * 주문 생성에 쓸 배송비 계산 유스케이스.
 */
public interface ShippingFeesUseCase {

    /**
     * 판매자별 배송비를 계산한다. 견적과 같은 지역 판정, 판매자 설정, 배송비 정책을 쓰므로 같은 입력이면 금액이 같다.
     * 판매자 설정이 없으면 기본 설정이 적용되고, 등록되지 않은 우편번호는 일반 지역이다.
     */
    ShippingFeesResult calculate(ShippingFeeQuoteQuery query);
}
