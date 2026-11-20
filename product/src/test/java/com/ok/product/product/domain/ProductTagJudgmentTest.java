package com.ok.product.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ProductTagJudgmentTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 9, 0);

	@Test
	void 재시도할_실패는_1분_5분_30분_2시간_뒤로_미루고_5회째에_최종_실패로_끝낸다() {
		// given
		ProductTagJudgment sut = ProductTagJudgment.pending(1L, NOW);

		// when
		sut.failRetryable("Jev 서버 오류", NOW);
		LocalDateTime afterFirst = sut.getNextAttemptAt();
		sut.failRetryable("Jev 서버 오류", NOW);
		sut.failRetryable("Jev 서버 오류", NOW);
		sut.failRetryable("Jev 서버 오류", NOW);
		LocalDateTime afterFourth = sut.getNextAttemptAt();
		ProductTagJudgmentStatus beforeLast = sut.getStatus();
		sut.failRetryable("Jev 서버 오류", NOW);

		// then
		assertSoftly(softly -> {
			softly.assertThat(afterFirst).isEqualTo(NOW.plusMinutes(1));
			softly.assertThat(afterFourth).isEqualTo(NOW.plusHours(2));
			softly.assertThat(beforeLast).isEqualTo(ProductTagJudgmentStatus.PENDING);
			softly.assertThat(sut.getStatus()).isEqualTo(ProductTagJudgmentStatus.FAILED);
			softly.assertThat(sut.getAttemptCount()).isEqualTo(5);
			softly.assertThat(sut.getLastError()).isEqualTo("Jev 서버 오류");
		});
	}

	@Test
	void 최종_실패는_바로_끝내고_지난_판정_결과는_그대로_둔다() {
		// given
		ProductTagJudgment sut = ProductTagJudgment.pending(1L, NOW);

		// when
		sut.fail("Jev가 요청을 거절했습니다. status=400");

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ProductTagJudgmentStatus.FAILED);
			softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
			softly.assertThat(sut.getJudgedAt()).isNull();
		});
	}

	@Test
	void 완료한_판정은_다시_완료하거나_실패로_기록할_수_없다() {
		// given
		ProductTagJudgment judgment = ProductTagJudgment.pending(1L, NOW);
		judgment.complete("v3", "jev-1.14.0", "T03", 0.95, NOW);

		// when & then
		assertThatThrownBy(() -> judgment.fail("늦게 온 실패"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("대기 중인 태그 판정이 아닙니다. productId=1, status=COMPLETED");
	}
}
