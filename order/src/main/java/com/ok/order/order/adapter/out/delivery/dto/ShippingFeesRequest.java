package com.ok.order.order.adapter.out.delivery.dto;

import java.util.List;

// postalCode: 주문 생성 요청의 배송지 우편번호 (도서산간 운임 계산에 쓴다)
public record ShippingFeesRequest(String postalCode, List<SellerItemAmountRequest> sellers) {
}
