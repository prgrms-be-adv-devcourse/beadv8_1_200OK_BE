package com.ok.product.product.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

// 환불 단위 재고 일부 해제 이력. 같은 환불 ID로 다시 와도 한 번만 반영하기 위한 기준
@Entity
@Table(name = "product_stock_partial_release", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductStockPartialRelease extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// refund 모듈의 환불 ID (값만 저장)
	@Column(name = "refund_id", nullable = false)
	private Long refundId;

	// order 모듈의 주문 ID (값만 저장)
	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "product_option_id", nullable = false)
	private Long productOptionId;

	@Column(nullable = false)
	private int quantity;

	public static ProductStockPartialRelease of(Long refundId, Long orderId, Long productOptionId, int quantity) {
		if (quantity < 1) {
			throw new IllegalArgumentException("해제 수량은 1개 이상이어야 합니다.");
		}
		ProductStockPartialRelease partialRelease = new ProductStockPartialRelease();
		partialRelease.refundId = refundId;
		partialRelease.orderId = orderId;
		partialRelease.productOptionId = productOptionId;
		partialRelease.quantity = quantity;
		return partialRelease;
	}

	// 같은 환불 ID의 재요청이 첫 요청과 같은 내용인지 (멱등 판단)
	public boolean matches(Long orderId, Long productOptionId, int quantity) {
		return Objects.equals(this.orderId, orderId)
			&& Objects.equals(this.productOptionId, productOptionId)
			&& this.quantity == quantity;
	}
}
