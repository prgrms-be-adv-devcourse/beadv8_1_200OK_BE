package com.ok.product.product.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.product.exception.ProductErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	@Column(name = "thumbnail_key", nullable = false)
	private String thumbnailKey;

	// 리뷰 집계 두 컬럼은 변경 감지로 쓰지 않는다. 생성 시 INSERT만 하고 이후엔 addReviewStats 쿼리로만 바꾼다.
	// 변경 감지 대상이면 판매자가 먼저 읽어 둔 상품을 저장할 때 그사이 달린 리뷰 집계를 옛 값으로 덮어쓴다
	@Column(name = "review_count", nullable = false, updatable = false)
	private int reviewCount;

	@Column(name = "rating_sum", nullable = false, updatable = false)
	private int ratingSum;

	@Version
	@Column(nullable = false)
	private Integer version;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	// 옵션은 소프트 삭제 (컬렉션에서 빼지 않고 delete 호출)
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL)
	private List<ProductOption> options = new ArrayList<>();

	// 상품 이미지는 컬렉션에서 빠지면 물리 삭제
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProductImage> images = new ArrayList<>();

	public static Product create(
			Long sellerId, String name, String descriptionHtml, ProductCategory category,
			ProductRegion region, List<ProductOptionSpec> optionSpecs, List<String> imageKeys
	) {
		validateOptionSpecs(optionSpecs);

		Product product = new Product();
		product.sellerId = sellerId;
		product.updateInfo(name, descriptionHtml, category, region);
		product.status = ProductStatus.READY;
		product.visibility = ProductVisibility.HIDDEN;
		for (int i = 0; i < optionSpecs.size(); i++) {
			product.addOption(optionSpecs.get(i), i);
		}
		product.replaceImages(imageKeys);
		product.recalculatePrice();
		return product;
	}

	public void updateInfo(String name, String descriptionHtml, ProductCategory category, ProductRegion region) {
		this.name = name;
		this.descriptionHtml = descriptionHtml;
		this.category = category;
		this.region = region;
	}

	public void replaceImages(List<String> imageKeys) {
		if (imageKeys == null || imageKeys.isEmpty()) {
			throw new IllegalArgumentException("상품 이미지는 1장 이상이어야 합니다.");
		}
		// 같은 이미지를 같은 순서로 보냈으면 DELETE·INSERT 없이 그대로 둔다
		if (images.stream().map(ProductImage::getObjectKey).toList().equals(imageKeys)) {
			return;
		}
		// orphanRemoval 컬렉션은 새 리스트로 바꾸지 않고 clear 후 다시 채운다
		images.clear();
		for (int i = 0; i < imageKeys.size(); i++) {
			images.add(ProductImage.create(this, imageKeys.get(i), i));
		}
		thumbnailKey = imageKeys.get(0);
	}

	// id가 있으면 수정, 없으면 추가, 요청에 없는 활성 옵션은 삭제 표시.
	public void mergeOptions(List<ProductOptionSpec> optionSpecs, LocalDateTime now) {
		validateOptionSpecs(optionSpecs);

		// 상품의 기존 옵션 Ids 저장
		Map<Long, ProductOption> activeById = new HashMap<>();
		for (ProductOption option : activeOptions()) {
			activeById.put(option.getId(), option);
		}

		// 요청의 id가 이 상품의 활성 옵션인지 확인
		Set<Long> requestedIds = new HashSet<>();
		for (ProductOptionSpec spec : optionSpecs) {
			if (spec.isNew()) {
				continue;
			}
			// 같은 id가 두 번 왔거나, 이 상품의 활성 옵션이 아니면 거부
			if (
					requestedIds.contains(spec.id()) ||
					!activeById.containsKey(spec.id())
			) {
				throw new RestApiException(ProductErrorCode.OPTION_NOT_FOUND);
			}
			requestedIds.add(spec.id());
		}

		// 요청에 없는 활성 옵션(기존 옵션)은 판매자가 삭제한것으로 판단
		for (ProductOption option : activeById.values()) {
			if (!requestedIds.contains(option.getId())) {
				option.delete(now);
			}
		}

		// 요청 순서대로 기존 옵션은 수정(id 유지), 새 옵션은 추가. 순서 = sortOrder
		for (int i = 0; i < optionSpecs.size(); i++) {
			ProductOptionSpec spec = optionSpecs.get(i);
			if (spec.isNew()) {
				addOption(spec, i);
			} else {
				activeById.get(spec.id()).update(spec.name(), spec.listPrice(), spec.salePrice(), i);
			}
		}

		// 옵션 가격이 바뀌었을 수 있으니 상품 대표 가격 다시 계산
		recalculatePrice();
	}

	public void changeStatus(ProductStatus status) {
		this.status = status;
	}

	public void changeVisibility(ProductVisibility visibility) {
		this.visibility = visibility;
	}


	// 배정 수량이 남아 있어도 삭제한다. allocated_quantity에는 결제 완료, 배송된 수량도 포함돼 현재 진행 중인 주문만 골라낼 수 없다.
	// 소프트 삭제라 옵션 행이 남으므로 이미 들어온 주문은 그대로 진행되고, 새 재고 배정만 막힌다.
	public void delete(LocalDateTime now) {
		if (deletedAt != null) {
			return;
		}
		deletedAt = now;
		// 상품에 포함된 각 옵션 소프트 삭제
		options.forEach(option -> option.delete(now));
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isOnSale() {
		return status == ProductStatus.ON_SALE && visibility == ProductVisibility.VISIBLE && !isDeleted();
	}

	// 상품 소유자 여부 체크
	public boolean isOwnedBy(Long memberId) {
		return sellerId.equals(memberId);
	}

	// 삭제되지 않은 옵션을 화면 순서대로
	public List<ProductOption> activeOptions() {
		return options.stream()
			.filter(option -> !option.isDeleted())
			.sorted(Comparator.comparingInt(ProductOption::getSortOrder))
			.toList();
	}

	// 활성된 옵션은 항상 1개 이상이다.
	public boolean isSoldOut() {
		return activeOptions().stream().mapToInt(ProductOption::availableStock).sum() <= 0;
	}

	// 대표 가격의 할인율
	public int discountRate() {
		return ProductOption.discountRate(minPriceListPrice, minSalePrice);
	}

	// 별점이 1~10(0.5점 단위)이라 2로 나눠 5점 만점, 소수 첫째 자리로 만든다. 리뷰가 없으면 0
	public double averageRating() {
		if (reviewCount == 0) {
			return 0;
		}
		return Math.round(ratingSum * 10.0 / (reviewCount * 2)) / 10.0;
	}

	public List<ProductOption> getOptions() {
		return Collections.unmodifiableList(options);
	}

	public List<ProductImage> getImages() {
		return Collections.unmodifiableList(images);
	}

	// 이미지 objectKey를 화면 표시 순서(0번이 대표)대로 돌려준다
	public List<String> imageKeys() {
		return images.stream()
			.sorted(Comparator.comparingInt(ProductImage::getSortOrder))
			.map(ProductImage::getObjectKey)
			.toList();
	}

	private void addOption(ProductOptionSpec spec, int sortOrder) {
		options.add(ProductOption.create(this, spec.name(), spec.listPrice(), spec.salePrice(), spec.totalStock(), sortOrder));
	}

	// 삭제되지 않은 옵션 중 판매가가 가장 낮은 옵션을 상품의 대표 가격으로 설정
	private void recalculatePrice() {
		ProductOption cheapest = activeOptions().stream()
			.min(Comparator.comparing(ProductOption::getSalePrice)
				.thenComparingInt(ProductOption::getSortOrder))
			.orElseThrow();
		minSalePrice = cheapest.getSalePrice();
		minPriceListPrice = cheapest.getListPrice();
	}

	// 요청 옵션들 안에서 옵션 정책에 알맞은지 확인
	// 20261012 기준 이름 중복만 체크
	private static void validateOptionSpecs(List<ProductOptionSpec> optionSpecs) {
		if (optionSpecs == null || optionSpecs.isEmpty()) {
			throw new IllegalArgumentException("옵션은 1개 이상이어야 합니다.");
		}
		Set<String> names = new HashSet<>();
		for (ProductOptionSpec spec : optionSpecs) {
			if (!names.add(spec.name())) {
				throw new RestApiException(ProductErrorCode.DUPLICATE_OPTION_NAME);
			}
		}
	}
}
