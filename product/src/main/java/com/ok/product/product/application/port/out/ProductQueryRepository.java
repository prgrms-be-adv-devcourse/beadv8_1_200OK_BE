package com.ok.product.product.application.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;

/**
 * 상품 목록 조회 출력 포트. 구현은 {@code adapter.out.persistence}의 Querydsl 구현이다.
 * 두 목록 모두 최신순(id 내림차순)이고 삭제한 상품은 나오지 않는다. null인 조건은 거르지 않는다.
 */
public interface ProductQueryRepository {

	// 구매자 목록. 공개한 상품만 나온다
	Page<Product> findVisibleProducts(ProductCategoryGroup categoryGroup, ProductCategory category, ProductRegion region,
		Pageable pageable);

	// 판매자 본인 목록. 숨긴 상품도 나온다
	Page<Product> findSellerProducts(Long sellerId, ProductStatus status, ProductVisibility visibility,
		Pageable pageable);

}
