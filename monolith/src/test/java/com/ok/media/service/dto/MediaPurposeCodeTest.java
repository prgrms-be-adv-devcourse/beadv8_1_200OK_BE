package com.ok.media.service.dto;

import com.ok.media.domain.MediaPurpose;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API용 {@link MediaPurposeCode}와 도메인 {@link MediaPurpose}의 상수가 같은지 확인한다.
 * 두 enum은 상수 이름으로 바꾸므로(valueOf), 한쪽에만 상수를 추가하면 그 용도로 업로드할 때 실행 중에 예외가 난다.
 */
class MediaPurposeCodeTest {

	@Test
	void 코드_enum과_도메인_enum의_상수_이름이_같다() {
		// when
		List<String> sut = Arrays.stream(MediaPurposeCode.values()).map(Enum::name).toList();

		// then
		assertThat(sut).containsExactlyInAnyOrderElementsOf(
				Arrays.stream(MediaPurpose.values()).map(Enum::name).toList());
	}
}
