package com.ok.media.service.dto;

import com.ok.media.domain.MediaPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API용 {@link MediaPurposeCode}와 도메인 {@link MediaPurpose}가 같은 이름의 상수끼리 오가는지 확인한다.
 * 빠진 case는 switch 식이 컴파일 단계에서 잡지만, 다른 상수에 잘못 연결한 case는 잡지 못한다.
 * from()이 없어 도메인에만 추가한 상수도 컴파일 단계에서 잡히지 않으므로 상수 목록도 비교한다.
 */
class MediaPurposeCodeTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("코드에서_도메인으로")
	void 코드_enum은_같은_이름의_도메인_상수로_바뀐다(MediaPurposeCode code, MediaPurpose sut) {
		// then
		assertThat(sut.name()).isEqualTo(code.name());
	}

	static Stream<Arguments> 코드에서_도메인으로() {
		return Arrays.stream(MediaPurposeCode.values()).map(code -> Arguments.of(code, code.toDomain()));
	}

	@Test
	void 도메인_enum의_상수는_모두_코드_enum에도_있다() {
		// when
		List<String> sut = Arrays.stream(MediaPurposeCode.values()).map(Enum::name).toList();

		// then
		assertThat(sut).containsExactlyInAnyOrderElementsOf(
				Arrays.stream(MediaPurpose.values()).map(Enum::name).toList());
	}
}
