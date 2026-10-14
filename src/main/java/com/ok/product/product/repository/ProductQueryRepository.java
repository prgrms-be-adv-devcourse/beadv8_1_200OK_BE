package com.ok.product.product.repository;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
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
		BooleanBuilder condition = visibleProductCondition(categoryGroup, category, region);
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
}
