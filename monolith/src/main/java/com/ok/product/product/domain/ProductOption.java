package com.ok.product.product.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.product.exception.ProductErrorCode;
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
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Column(name = "list_price", nullable = false)
	private Long listPrice;

	@Column(name = "sale_price", nullable = false)
	private Long salePrice;

	// 재고 두 컬럼은 변경 감지로 쓰지 않는다. 생성 시 INSERT만 하고 이후엔 리포지토리 쿼리로만 바꾼다
	@Column(name = "total_stock", nullable = false, updatable = false)
	private int totalStock;

	@Column(name = "allocated_quantity", nullable = false, updatable = false)
	private int allocatedQuantity;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	static ProductOption create(Product product, String name, Long listPrice, Long salePrice,
		int totalStock, int sortOrder) {
		ProductOption option = new ProductOption();
		option.product = product;
		option.name = name;
		option.changePrice(listPrice, salePrice);
		option.totalStock = totalStock;
		option.sortOrder = sortOrder;
		return option;
	}

	// 총재고는 여기서 바꾸지 않음
	void update(String name, Long listPrice, Long salePrice, int sortOrder) {
		this.name = name;
		changePrice(listPrice, salePrice);
		this.sortOrder = sortOrder;
	}

	void delete(LocalDateTime now) {
		if (deletedAt == null) {
			deletedAt = now;
		}
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public int availableStock() {
		return totalStock - allocatedQuantity;
	}

	// 재고를 뺀 판매 조건
	public boolean isOnSale() {
		return !isDeleted() && product.isOnSale();
	}

	public boolean isPurchasable() {
		return isOnSale() && availableStock() > 0;
	}

	public int discountRate() {
		return discountRate(listPrice, salePrice);
	}

	// 할인율은 저장하지 않고 정가와 판매가로 계산한다 (소수점 버림). 상품 대표 할인율도 같은 식을 쓴다
	static int discountRate(Long listPrice, Long salePrice) {
		if (listPrice == 0) {
			return 0;
		}
		return (int)((listPrice - salePrice) * 100 / listPrice);
	}

	private void changePrice(Long listPrice, Long salePrice) {
		Long resolvedListPrice = listPrice != null ? listPrice : salePrice;
		if (salePrice > resolvedListPrice) {
			throw new RestApiException(ProductErrorCode.PRICE_EXCEEDS_LIST_PRICE);
		}
		this.listPrice = resolvedListPrice;
		this.salePrice = salePrice;
	}
}
