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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API용 *Code enum과 도메인 enum의 상수가 같은지 확인한다.
 * *Code enum은 컨트롤러가 도메인 enum을 알지 않도록 두고, 서비스 계층에서 도메인 enum과 바꾼다.
 * 두 enum은 상수 이름으로 서로 바꾸므로(valueOf), 한쪽에만 상수를 추가하면 그 값이 오갈 때 실행 중에 예외가 난다.
 */
class ProductCodeConsistencyTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("코드와_도메인_enum")
	void 코드_enum과_도메인_enum의_상수_이름이_같다(Class<? extends Enum<?>> codeType, Class<? extends Enum<?>> domainType) {
		// when
		List<String> sut = constantNames(codeType);

		// then
		assertThat(sut).containsExactlyElementsOf(constantNames(domainType));
	}

	static Stream<Arguments> 코드와_도메인_enum() {
		return Stream.of(
			Arguments.of(ProductCategoryCode.class, ProductCategory.class),
			Arguments.of(ProductCategoryGroupCode.class, ProductCategoryGroup.class),
			Arguments.of(ProductRegionCode.class, ProductRegion.class),
			Arguments.of(ProductStatusCode.class, ProductStatus.class),
			Arguments.of(ProductVisibilityCode.class, ProductVisibility.class)
		);
	}

	private static List<String> constantNames(Class<? extends Enum<?>> type) {
		return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
	}
}
