package com.ok.product.product.domain;

import org.springframework.data.domain.Persistable;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 하나의 검색용 정규화 값. 상품과 1:1이다.
 * {@link Product}에 컬럼을 두지 않고 따로 둔 이유는 태그 판정(F9)이 비동기로 {@code keywords}를 고칠 때 상품 version이
 * 오르지 않게 하기 위해서다. version이 오르면 그동안 판매자의 상품 수정이 동시 수정으로 실패한다.
 * 상품은 ID로만 가리키고 연관관계를 걸지 않는다. 검색 행을 다룰 때 상품을 함께 불러오지 않게 하기 위해서다.
 *
 * <p>상품 ID를 PK로 직접 할당하므로 {@link Persistable}을 구현한다. 구현하지 않으면 {@code save()}가 새 행도
 * 있는 행으로 보고 {@code merge}를 해서 INSERT 전에 SELECT가 한 번 더 나간다.
 */
@Entity
@Table(name = "product_search", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSearch extends BaseTime implements Persistable<Long> {

	@Id
	@Column(name = "product_id")
	private Long productId;

	@Column(name = "name_compact", nullable = false, length = 100)
	private String nameCompact;

	@Column(name = "name_jamo", nullable = false, length = 400)
	private String nameJamo;

	@Column(name = "search_body", nullable = false, columnDefinition = "TEXT")
	private String searchBody;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String keywords;

	// 1글자 검색어로 이 상품을 찾을 한 글자 품목이다("제주감귤"이면 "귤"). 고르는 규칙은 ProductItemDictionary에 있다
	@Column(name = "search_item", length = 20)
	private String searchItem;

	@Column(name = "normalizer_version", nullable = false)
	private int normalizerVersion;

	public static ProductSearch create(Long productId, String nameCompact, String nameJamo, String searchBody,
		String searchItem, int normalizerVersion) {
		ProductSearch productSearch = new ProductSearch();
		productSearch.productId = productId;
		productSearch.keywords = "";
		productSearch.refresh(nameCompact, nameJamo, searchBody, searchItem, normalizerVersion);
		return productSearch;
	}

	/**
	 * 상품 정보에서 계산한 값으로 다시 채운다.
	 * {@code keywords}는 건드리지 않는다. 태그 판정(F9)이 비동기로 채우는 값이라, 판매자가 상품을 고칠 때마다 지우면 안 된다.
	 */
	public void refresh(String nameCompact, String nameJamo, String searchBody, String searchItem,
		int normalizerVersion) {
		this.nameCompact = nameCompact;
		this.nameJamo = nameJamo;
		this.searchBody = searchBody;
		this.searchItem = searchItem;
		this.normalizerVersion = normalizerVersion;
	}

	@Override
	public Long getId() {
		return productId;
	}

	// 생성 시각은 처음 저장할 때 채워진다. DB에서 읽은 행은 이미 값이 있다.
	// JPA auditing(@CreatedDate)이 켜져 있다는 가정에 기댄다. 읽어 온 행을 save()로 다시 저장하는 경로를 만들 때 이 가정을 확인한다
	@Override
	public boolean isNew() {
		return getCreatedAt() == null;
	}
}
