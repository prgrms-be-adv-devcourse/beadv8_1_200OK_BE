package com.ok.order.order.client.dto;

import java.util.List;

public record ShippingFeesResponse(List<SellerShippingFeeResponse> sellers) {
}
