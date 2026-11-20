package com.ok.product.product.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 하나의 태그 판정 상태이자 판정 요청이다. 상품마다 한 행이다.
 * 대기에서 완료나 최종 실패로 끝나고, 상품명이나 옵션명이 바뀌면 어느 상태에서든 처음 대기로 돌아간다.
 * 재시도할 실패는 리뷰 분석 요청과 같은 규칙이다. 대기로 둔 채 다음 시도 시각을 1분, 5분, 30분, 2시간 뒤로 미루고,
 * 처음 호출을 포함해 5회째도 실패하면 최종 실패로 끝낸다.
 * {@code requestedAt}은 요청 세대다. 작업자는 꺼낼 때 읽은 값과 저장할 때의 값이 다르면 판정 중에 상품이 바뀐 것으로 보고 결과를 버린다.
 */
@Entity
@Table(name = "product_tag_judgment", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductTagJudgment extends BaseTime {

	private static final int LAST_ERROR_MAX_LENGTH = 500;

	// n번째 실패 뒤에는 n번째 값만큼 기다린다. 표를 바꾸면 최대 시도 횟수도 따라 바뀐다
	private static final List<Duration> RETRY_DELAYS = List.of(
		Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));
	// 처음 호출을 포함한다
	private static final int MAX_ATTEMPTS = RETRY_DELAYS.size() + 1;
	// 작업자 한 번 실행의 최악(20건 모두 연결 3초 + 응답 10초 초과, 약 4분 20초)보다 길어야 처리 중인 요청을 다른 작업자가 다시 꺼내지 않는다
	private static final Duration CLAIM_DURATION = Duration.ofMinutes(10);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "product_id", nullable = false, updatable = false)
	private Long productId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProductTagJudgmentStatus status;

	@Column(name = "tag_version", length = 20)
	private String tagVersion;

	// 모델명은 50자까지만 저장된다. 더 긴 모델로 바꾸면 결과를 저장하지 못해 요청이 대기로 남고, 임대(10분)가 끝날 때마다 다시 판정된다
	@Column(length = 50)
	private String model;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at", nullable = false)
	private LocalDateTime nextAttemptAt;

	@Column(name = "requested_at", nullable = false)
	private LocalDateTime requestedAt;

	@Column(name = "judged_at")
	private LocalDateTime judgedAt;

	@Column(name = "top_tag_id", length = 10)
	private String topTagId;

	@Column(name = "top_score")
	private Double topScore;

	@Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
	private String lastError;

	public static ProductTagJudgment pending(Long productId, LocalDateTime now) {
		ProductTagJudgment judgment = new ProductTagJudgment();
		judgment.productId = productId;
		judgment.status = ProductTagJudgmentStatus.PENDING;
		judgment.nextAttemptAt = now;
		judgment.requestedAt = now;
		return judgment;
	}

	/**
	 * 작업자가 꺼낸 요청을 한동안 다른 작업자가 꺼내지 못하게 다음 시도 시각을 미룬다.
	 * 판정이 끝나면 완료나 실패 기록이 상태와 다음 시도 시각을 다시 정하고, 작업자가 도중에 멈추면 미룬 시각이 지나 다시 꺼내진다.
	 */
	public void claim(LocalDateTime now) {
		validatePending();
		nextAttemptAt = now.plus(CLAIM_DURATION);
	}

	/**
	 * 판정을 마친다. 지난 판정의 결과(버전, 모델, 가장 높았던 태그)는 이번 값으로 바꾼다.
	 *
	 * @param topTagId 확률이 가장 높았던 태그. 붙인 태그가 0개여도 남겨 아깝게 떨어졌는지 볼 수 있게 한다
	 */
	public void complete(String tagVersion, String model, String topTagId, Double topScore, LocalDateTime now) {
		validatePending();
		status = ProductTagJudgmentStatus.COMPLETED;
		this.tagVersion = tagVersion;
		this.model = model;
		this.topTagId = topTagId;
		this.topScore = topScore;
		judgedAt = now;
		lastError = null;
	}

	/**
	 * 다시 시도하지 않고 최종 실패로 끝낸다. 운영자가 원인을 볼 수 있게 마지막 오류를 남긴다.
	 * 지난 판정 결과(태그, 판정 시각)는 그대로 둔다. 상품명이 바뀐 뒤의 실패라도 태그가 아예 없는 것보다 낫다고 본다.
	 */
	public void fail(String error) {
		validatePending();
		status = ProductTagJudgmentStatus.FAILED;
		attemptCount++;
		lastError = truncate(error);
	}

	/**
	 * 다시 보내면 성공할 수 있는 실패를 기록하고 다음 시도 시각을 미룬다. 최대 시도 횟수를 다 쓰면 최종 실패로 끝낸다.
	 */
	public void failRetryable(String error, LocalDateTime now) {
		validatePending();
		attemptCount++;
		lastError = truncate(error);
		if (attemptCount >= MAX_ATTEMPTS) {
			status = ProductTagJudgmentStatus.FAILED;
			return;
		}
		nextAttemptAt = now.plus(RETRY_DELAYS.get(attemptCount - 1));
	}

	public boolean isRequestedAt(LocalDateTime requestedAt) {
		return this.requestedAt.equals(requestedAt);
	}

	// 같은 세대의 요청을 두 작업자가 끝내는 것은 임대가 끝난 뒤에야 생기는 일이라 버그로 보고 저장을 막는다
	private void validatePending() {
		if (status != ProductTagJudgmentStatus.PENDING) {
			throw new IllegalStateException("대기 중인 태그 판정이 아닙니다. productId=" + productId + ", status=" + status);
		}
	}

	private static String truncate(String error) {
		if (error == null || error.length() <= LAST_ERROR_MAX_LENGTH) {
			return error;
		}
		return error.substring(0, LAST_ERROR_MAX_LENGTH);
	}
}
