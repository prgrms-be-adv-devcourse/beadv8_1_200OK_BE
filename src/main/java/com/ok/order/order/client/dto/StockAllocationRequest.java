package com.ok.order.order.client.dto;

import java.util.List;

public record StockAllocationRequest(Long orderId, List<StockAllocationItemRequest> items) {
}
