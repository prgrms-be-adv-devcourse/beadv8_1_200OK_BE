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

/**
 * 상품에 붙은 상황·용도 태그 하나다. 마지막 판정에서 기준값을 넘은 태그만 행이 있다.
 * 상품은 ID로만 가리킨다. 태그를 바꿀 때 상품을 불러오거나 상품 version을 올리지 않기 위해서다.
 */
@Entity
@Table(name = "product_tag", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductTag extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "product_id", nullable = false, updatable = false)
	private Long productId;

	@Column(name = "tag_id", nullable = false, length = 10, updatable = false)
	private String tagId;

	public static ProductTag create(Long productId, String tagId) {
		ProductTag productTag = new ProductTag();
		productTag.productId = productId;
		productTag.tagId = tagId;
		return productTag;
	}
}
