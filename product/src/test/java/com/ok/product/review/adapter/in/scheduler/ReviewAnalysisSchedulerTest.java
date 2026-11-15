package com.ok.product.review.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import com.ok.product.review.application.service.ReviewKeywordAnalysisFacade;
import com.ok.product.review.application.service.ReviewSentimentAnalysisFacade;

/**
 * 리뷰 분석 작업자가 켜지는 조건, 실행 간격, 하는 일을 고정한다.
 * 작업자는 운영 설정에서만 켜져 통합 테스트가 거치지 않으므로, 구조를 옮길 때 바뀌지 않았는지 여기서 확인한다.
 */
class ReviewAnalysisSchedulerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(ReviewKeywordAnalysisFacade.class, () -> mock(ReviewKeywordAnalysisFacade.class))
		.withBean(ReviewSentimentAnalysisFacade.class, () -> mock(ReviewSentimentAnalysisFacade.class))
		.withUserConfiguration(ReviewKeywordAnalysisScheduler.class, ReviewSentimentAnalysisScheduler.class);

	@Test
	void 설정이_없으면_두_작업자_모두_뜨지_않는다() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(ReviewKeywordAnalysisScheduler.class)
			.doesNotHaveBean(ReviewSentimentAnalysisScheduler.class));
	}

	@Test
	void 키워드_작업자는_키워드_설정만으로_켜진다() {
		contextRunner.withPropertyValues("app.review-analysis.keyword-worker.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(ReviewKeywordAnalysisScheduler.class)
				.doesNotHaveBean(ReviewSentimentAnalysisScheduler.class));
	}

	@Test
	void 감성_작업자는_감성_설정만으로_켜진다() {
		contextRunner.withPropertyValues("app.review-analysis.worker.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(ReviewSentimentAnalysisScheduler.class)
				.doesNotHaveBean(ReviewKeywordAnalysisScheduler.class));
	}

	@Test
	void 키워드_작업자는_실행마다_대기_요청_분석을_맡긴다() {
		contextRunner.withPropertyValues("app.review-analysis.keyword-worker.enabled=true")
			.run(context -> {
				// given
				ReviewKeywordAnalysisScheduler sut = context.getBean(ReviewKeywordAnalysisScheduler.class);

				// when
				sut.run();

				// then
				then(context.getBean(ReviewKeywordAnalysisFacade.class)).should().analyzePending();
			});
	}

	@Test
	void 감성_작업자는_실행마다_대기_요청_분석을_맡긴다() {
		contextRunner.withPropertyValues("app.review-analysis.worker.enabled=true")
			.run(context -> {
				// given
				ReviewSentimentAnalysisScheduler sut = context.getBean(ReviewSentimentAnalysisScheduler.class);

				// when
				sut.run();

				// then
				then(context.getBean(ReviewSentimentAnalysisFacade.class)).should().analyzePending();
			});
	}

	// 분석이 길어져도 밀린 실행을 몰아서 하지 않도록 이전 실행이 끝난 때부터 5초를 센다
	@Test
	void 두_작업자_모두_이전_실행이_끝나고_5초_뒤에_다시_돈다() throws NoSuchMethodException {
		// when
		Scheduled keyword = ReviewKeywordAnalysisScheduler.class.getMethod("run").getAnnotation(Scheduled.class);
		Scheduled sentiment = ReviewSentimentAnalysisScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

		// then
		assertThat(keyword).isNotNull();
		assertThat(sentiment).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(keyword.fixedDelay()).isEqualTo(5);
			softly.assertThat(keyword.timeUnit()).isEqualTo(TimeUnit.SECONDS);
			softly.assertThat(keyword.fixedRate()).isEqualTo(-1);
			softly.assertThat(keyword.cron()).isEmpty();
			softly.assertThat(sentiment.fixedDelay()).isEqualTo(5);
			softly.assertThat(sentiment.timeUnit()).isEqualTo(TimeUnit.SECONDS);
			softly.assertThat(sentiment.fixedRate()).isEqualTo(-1);
			softly.assertThat(sentiment.cron()).isEmpty();
		});
	}
}
