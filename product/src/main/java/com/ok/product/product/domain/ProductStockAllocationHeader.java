package com.ok.product.product.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 주문 단위 재고 배정 상태. 같은 주문의 배정·해제가 이 행 잠금에서 차례로 처리되고,
// 해제가 배정보다 먼저 와도 RELEASED로 남아 이후 배정을 막는다
@Entity
@Table(name = "product_stock_allocation_header", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductStockAllocationHeader extends BaseTime {

	// order 모듈의 주문 ID (값만 저장)
	@Id
	@Column(name = "order_id")
	private Long orderId;

	// 생성과 상태 전이는 네이티브 upsert 쿼리로만 한다(저장소에 save가 없다). 상태는 JPA로 바꾸지 않아 updatable = false로 둔다
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private StockAllocationStatus status;
}
