package com.ok.product.product.repository;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

import static com.ok.product.product.domain.QProduct.product;

@Repository
@RequiredArgsConstructor
public class ProductQueryRepository {

	private final JPAQueryFactory queryFactory;

	// 구매자 목록
	public Page<Product> findVisibleProducts(
			ProductCategoryGroup categoryGroup,
			ProductCategory category,
			ProductRegion region,
			Pageable pageable
	) {
		return fetchPage(visibleProductCondition(categoryGroup, category, region), pageable);
	}

	// 판매자 본인 목록. 숨긴 상품도 나오고 삭제한 상품은 나오지 않는다.
	public Page<Product> findSellerProducts(
			Long sellerId,
			ProductStatus status,
			ProductVisibility visibility,
			Pageable pageable
	) {
		return fetchPage(sellerProductCondition(sellerId, status, visibility), pageable);
	}

	// 최신순(id 내림차순) offset 페이지. count 쿼리는 content가 한 페이지에 다 들어오면 생략된다
	private Page<Product> fetchPage(BooleanBuilder condition, Pageable pageable) {
		List<Product> content = queryFactory
			.selectFrom(product)
			.where(condition)
			.orderBy(product.id.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		return PageableExecutionUtils.getPage(content, pageable, () -> Objects.requireNonNullElse(queryFactory
			.select(product.count())
			.from(product)
			.where(condition)
			.fetchOne(), 0L));
	}

	private BooleanBuilder visibleProductCondition(ProductCategoryGroup categoryGroup, ProductCategory category, ProductRegion region) {
		BooleanBuilder condition = new BooleanBuilder()
			.and(product.visibility.eq(ProductVisibility.VISIBLE))
			.and(product.deletedAt.isNull());

		if (categoryGroup != null) {
			condition.and(product.category.in(ProductCategory.findAllByGroup(categoryGroup)));
		}
		if (category != null) {
			condition.and(product.category.eq(category));
		}
		if (region != null) {
			condition.and(product.region.eq(region));
		}
		return condition;
	}

	private BooleanBuilder sellerProductCondition(Long sellerId, ProductStatus status, ProductVisibility visibility) {
		BooleanBuilder condition = new BooleanBuilder()
			.and(product.sellerId.eq(sellerId))
			.and(product.deletedAt.isNull());

		if (status != null) {
			condition.and(product.status.eq(status));
		}
		if (visibility != null) {
			condition.and(product.visibility.eq(visibility));
		}
		return condition;
	}
}
