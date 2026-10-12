package com.ok.product.product.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTime {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "seller_id", nullable = false)
	private Long sellerId;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "description_html", nullable = false, columnDefinition = "MEDIUMTEXT")
	private String descriptionHtml;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ProductCategory category;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ProductRegion region;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProductStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProductVisibility visibility;

	// 옵션 중 최저 판매가와 그 옵션의 정가
	@Column(name = "min_sale_price", nullable = false)
	private Long minSalePrice;

	@Column(name = "min_price_list_price", nullable = false)
	private Long minPriceListPrice;

	// product_image sort_order 0번 복사본
	@Column(name = "thumbnail_url", nullable = false, length = 500)
	private String thumbnailUrl;

	@Column(name = "review_count", nullable = false)
	private int reviewCount;

	@Column(name = "rating_sum", nullable = false)
	private int ratingSum;

	@Version
	@Column(nullable = false)
	private Integer version;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	// 옵션은 소프트 삭제 (컬렉션에서 빼지 않고 delete 호출)
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL)
	private List<ProductOption> options = new ArrayList<>();

	// 이미지는 컬렉션에서 빠지면 물리 삭제
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProductImage> images = new ArrayList<>();
}
