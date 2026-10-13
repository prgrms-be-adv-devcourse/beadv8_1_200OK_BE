package com.ok.product.product.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 주문별 재고 배정 기록. 같은 주문의 배정·해제가 여러 번 와도 한 번만 반영하기 위한 기준
@Entity
@Table(name = "product_stock_allocation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductStockAllocation extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// order 모듈의 주문 ID (값만 저장)
	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "product_option_id", nullable = false)
	private Long productOptionId;

	@Column(nullable = false)
	private int quantity;

	// ALLOCATED → RELEASED 전이는 조건부 UPDATE 쿼리로만 한다 (동시 해제 시 중복 반영 방지)
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private StockAllocationStatus status;

	public static ProductStockAllocation allocate(Long orderId, Long productOptionId, int quantity) {
		if (quantity < 1) {
			throw new IllegalArgumentException("배정 수량은 1개 이상이어야 합니다.");
		}
		ProductStockAllocation allocation = new ProductStockAllocation();
		allocation.orderId = orderId;
		allocation.productOptionId = productOptionId;
		allocation.quantity = quantity;
		allocation.status = StockAllocationStatus.ALLOCATED;
		return allocation;
	}
}
