package com.ok.product.product.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name= "product_id", nullable = false)
	private Product product;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Column(name = "list_price", nullable = false)
	private Long listPrice;

	@Column(name = "sale_price", nullable = false)
	private Long salePrice;

	@Column(name = "total_stock", nullable = false, updatable = false)
	private int totalStock;

	@Column(name = "allocated_quantity", nullable = false, updatable = false)
	private int allocatedQuantity;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;


}
