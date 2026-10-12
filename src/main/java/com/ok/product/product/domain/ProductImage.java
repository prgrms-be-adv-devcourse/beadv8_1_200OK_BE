package com.ok.product.product.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 소프트 삭제 없음. Product의 images 컬렉션에서 빠지면 orphanRemoval로 물리 삭제
@Entity
@Table(name = "product_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	@Column(nullable = false, length = 500)
	private String url;

	// 0번이 대표 이미지 (product.thumbnail_url로 복사)
	@Column(name = "sort_order", nullable = false)
	private int sortOrder;
}
