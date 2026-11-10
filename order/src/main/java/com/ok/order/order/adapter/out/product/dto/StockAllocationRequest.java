package com.ok.order.order.adapter.out.product.dto;

import java.util.List;

public record StockAllocationRequest(Long orderId, List<StockAllocationItemRequest> items) {
}
