package com.ok.product.product.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.application.dto.ProductTagTarget;
import com.ok.product.product.application.port.in.ProductTagJudgmentUseCase;
import com.ok.product.product.application.port.out.ProductTagJudge;
import com.ok.product.product.exception.ProductTagJudgeFailedException;
import com.ok.product.product.exception.ProductTagJudgeUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 태그 판정 대기 요청을 꺼내 판정기로 판정하고 결과를 저장한다. 리뷰 키워드 작업자와 같은 규칙이다.
 * 판정기는 외부 서비스라 트랜잭션 밖(여기)에서 부르고, 꺼내기와 결과 저장은 {@link ProductTagService}의 짧은 트랜잭션으로 나눈다.
 * 판정기 실패는 {@link ProductTagJudgeFailedException}이 재시도할 실패라고 밝힌 것만 백오프로 다시 시도하고, 나머지는 바로 최종 실패다.
 * 판정기를 쓸 수 없으면({@link ProductTagJudgeUnavailableException}) 실패로 기록하지 않고 이번 실행을 멈춘 뒤 5분 동안 쉰다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductTagJudgmentFacade implements ProductTagJudgmentUseCase {

	private static final int BATCH_SIZE = 20;
	// 임대 시간(10분)보다 짧게 잡아, 키를 고치면 꺼내 둔 요청의 임대가 끝나기 전에 작업자가 다시 돈다
	private static final Duration UNAVAILABLE_PAUSE = Duration.ofMinutes(5);

	private final ProductTagService productTagService;
	private final ProductTagJudge productTagJudge;
	private final Clock clock;

	// 앱 인스턴스마다 따로 쉰다. 여러 대면 각 대가 처음 거절을 받을 때 스스로 멈춘다
	private volatile LocalDateTime pausedUntil;

	@Override
	public void judgePending() {
		LocalDateTime until = pausedUntil;
		if (until != null && LocalDateTime.now(clock).isBefore(until)) {
			return;
		}
		List<ProductTagTarget> targets = productTagService.findPendingTargets(BATCH_SIZE);
		for (ProductTagTarget target : targets) {
			try {
				judge(target);
			} catch (ProductTagJudgeUnavailableException e) {
				// 남은 요청도 같은 이유로 실패하므로 부르지 않는다. 꺼낸 요청은 대기 상태로 임대(10분)가 끝나면 다시 꺼내지고, 시도 횟수도 그대로다
				pausedUntil = LocalDateTime.now(clock).plus(UNAVAILABLE_PAUSE);
				log.error("상품 태그 판정기를 쓸 수 없어 작업을 멈춤 judgmentId={}, pausedUntil={}", target.judgmentId(),
					pausedUntil, e);
				return;
			} catch (RuntimeException e) {
				// 한 건이 실패해도 나머지는 계속. 저장하지 못한 요청은 대기로 남아, 꺼낼 때 건 임대(10분)가 끝나면 다시 꺼낸다
				log.error("상품 태그 판정 결과 저장 실패 judgmentId={}, productId={}", target.judgmentId(), target.productId(), e);
			}
		}
	}

	private void judge(ProductTagTarget target) {
		ProductTagScores scores;
		try {
			scores = productTagJudge.judge(target.input());
		} catch (ProductTagJudgeUnavailableException e) {
			// 아래 RuntimeException 처리에 걸려 최종 실패가 되지 않게 그대로 올린다
			throw e;
		} catch (ProductTagJudgeFailedException e) {
			productTagService.fail(target, e, e.isRetryable());
			return;
		} catch (RuntimeException e) {
			// 판정기가 분류하지 못한 예외(판정기 버그 등)는 다시 보내도 같은 결과일 가능성이 커 재시도하지 않는다
			productTagService.fail(target, e, false);
			return;
		}
		productTagService.complete(target, scores);
	}
}
