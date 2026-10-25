package com.ok.product.product.domain;

import com.ok.product.ProductSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 소프트 삭제 없음. Product의 images 컬렉션에서 빠지면 orphanRemoval로 물리 삭제
@Entity
@Table(name = "product_image", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	// media 모듈의 objectKey. URL은 조회할 때 media 모듈에서 만든다
	@Column(name = "object_key", nullable = false)
	private String objectKey;

	// 0번이 대표 이미지 (product.thumbnail_key로 복사)
	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	// 이미지는 Product를 통해서만
	static ProductImage create(Product product, String objectKey, int sortOrder) {
		ProductImage image = new ProductImage();
		image.product = product;
		image.objectKey = objectKey;
		image.sortOrder = sortOrder;
		return image;
	}
}
