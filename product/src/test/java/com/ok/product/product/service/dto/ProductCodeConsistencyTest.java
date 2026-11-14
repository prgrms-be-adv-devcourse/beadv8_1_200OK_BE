package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API용 *Code enum과 도메인 enum이 같은 이름의 상수끼리 오가는지 확인한다.
 * 빠진 case는 switch 식이 컴파일 단계에서 잡지만, 다른 상수에 잘못 연결한 case(예: PAUSED -> ENDED)는 잡지 못한다.
 */
class ProductCodeConsistencyTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("코드에서_도메인으로")
	void 코드_enum은_같은_이름의_도메인_상수로_바뀐다(Enum<?> code, Enum<?> sut) {
		// then
		assertThat(sut.name()).isEqualTo(code.name());
	}

	static Stream<Arguments> 코드에서_도메인으로() {
		return Stream.of(
			mappings(ProductCategoryCode.values(), ProductCategoryCode::toDomain),
			mappings(ProductCategoryGroupCode.values(), ProductCategoryGroupCode::toDomain),
			mappings(ProductRegionCode.values(), ProductRegionCode::toDomain),
			mappings(ProductStatusCode.values(), ProductStatusCode::toDomain),
			mappings(ProductVisibilityCode.values(), ProductVisibilityCode::toDomain)
		).flatMap(Function.identity());
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("도메인에서_코드로")
	void 도메인_enum은_같은_이름의_코드_상수로_바뀐다(Enum<?> domain, Enum<?> sut) {
		// then
		assertThat(sut.name()).isEqualTo(domain.name());
	}

	static Stream<Arguments> 도메인에서_코드로() {
		return Stream.of(
			mappings(ProductCategory.values(), ProductCategoryCode::from),
			mappings(ProductCategoryGroup.values(), ProductCategoryGroupCode::from),
			mappings(ProductRegion.values(), ProductRegionCode::from),
			mappings(ProductStatus.values(), ProductStatusCode::from),
			mappings(ProductVisibility.values(), ProductVisibilityCode::from)
		).flatMap(Function.identity());
	}

	private static <T extends Enum<T>> Stream<Arguments> mappings(T[] values, Function<T, ? extends Enum<?>> convert) {
		return Arrays.stream(values).map(value -> Arguments.of(value, convert.apply(value)));
	}
}
