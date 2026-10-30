package com.ok.order.order.client.dto;

import java.util.List;

// postalCode: 배송지 우편번호, 주문서 조회처럼 배송지를 정하기 전이면 null (도서산간 운임 0원으로 계산)
public record ShippingFeesRequest(String postalCode, List<SellerItemAmountRequest> sellers) {
}
