package com.ok.order.order.application.port.out;

import com.ok.order.order.domain.SellerShippingFee;

import java.util.Map;

/**
 * 주문이 배송 모듈에 부탁하는 일. 구현은 {@code adapter.out.delivery}에 있다.
 */
public interface DeliveryPort {

    /**
     * 판매자별 배송비와 도서산간 운임을 한 번에 조회한다.
     * 판매자 배송 설정과 지역 데이터는 배송 모듈이 가지므로 계산도 배송 모듈이 한다.
     *
     * @param itemAmountBySeller 판매자 ID → 그 판매자 상품 금액 합계 (무료배송 기준과 비교하는 값)
     * @return 판매자 ID → 배송비. 배송 모듈이 응답에서 뺀 판매자는 들어 있지 않다
     */
    Map<Long, SellerShippingFee> findShippingFees(String postalCode, Map<Long, Long> itemAmountBySeller);
}
