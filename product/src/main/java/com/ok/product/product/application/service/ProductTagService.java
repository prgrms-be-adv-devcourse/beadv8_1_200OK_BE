package com.ok.product.product.application.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.application.dto.ProductTagBackfillResult;
import com.ok.product.product.application.dto.ProductTagInput;
import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.application.dto.ProductTagTarget;
import com.ok.product.product.application.dto.UntaggedProductQuery;
import com.ok.product.product.application.dto.UntaggedProductResult;
import com.ok.product.product.application.port.in.ProductTagBackfillUseCase;
import com.ok.product.product.application.port.in.UntaggedProductUseCase;
import com.ok.product.product.application.port.out.ProductRepository;
import com.ok.product.product.application.port.out.ProductSearchRepository;
import com.ok.product.product.application.port.out.ProductTagJudgmentRepository;
import com.ok.product.product.application.port.out.ProductTagRepository;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductTag;
import com.ok.product.product.domain.ProductTagJudgment;
import com.ok.product.product.domain.ProductTagJudgmentStatus;
import com.ok.product.product.exception.ProductErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품 태그 판정 요청을 꺼내고 결과를 저장하며, 판정 대기열을 채우고 태그 0개 상품을 보여 준다.
 * 판정기 호출은 트랜잭션 밖({@link ProductTagJudgmentFacade})에서 하고, 여기서는 DB 작업만 짧은 트랜잭션으로 한다.
 * 판정 결과는 상품 행을 건드리지 않고 태그, 판정 행, 검색 키워드에만 쓴다. 상품 version이 오르면 그동안 판매자의 수정이 동시 수정으로 실패한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductTagService implements ProductTagBackfillUseCase, UntaggedProductUseCase {

	private final ProductTagJudgmentRepository judgmentRepository;
	private final ProductTagRepository productTagRepository;
	private final ProductRepository productRepository;
	private final ProductSearchRepository productSearchRepository;
	private final ProductTagList tagList;
	private final Clock clock;

	/**
	 * 다음 시도 시각이 지난 대기 요청을 오래 기다린 순서로 꺼내 판정기에 넘길 상품 내용과 함께 돌려준다.
	 * 상품이 삭제됐거나 없으면 판정기에 넘기지 않고 판정 행을 지운다.
	 * 넘기는 요청은 다음 시도 시각을 미뤄, 앱이 여러 대여도 다른 작업자가 같은 요청을 꺼내 판정기를 두 번 부르지 않게 한다.
	 */
	@Transactional
	public List<ProductTagTarget> findPendingTargets(int limit) {
		LocalDateTime now = now();
		List<ProductTagJudgment> judgments = judgmentRepository
			.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
				ProductTagJudgmentStatus.PENDING, now, Limit.of(limit));
		// 상품마다 옵션을 지연 로딩하지 않게 한 번에 읽는다
		Map<Long, Product> products = productRepository.findAllWithOptionsByIdIn(
				judgments.stream().map(ProductTagJudgment::getProductId).toList()).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));

		List<ProductTagTarget> targets = new ArrayList<>();
		for (ProductTagJudgment judgment : judgments) {
			Product product = products.get(judgment.getProductId());
			if (product == null || product.isDeleted()) {
				removeJudgmentOfDeletedProduct(judgment);
				continue;
			}
			judgment.claim(now);
			ProductTagInput input = new ProductTagInput(product.getId(), product.getName(),
				product.activeOptions().stream().map(ProductOption::getName).toList());
			targets.add(new ProductTagTarget(judgment.getId(), product.getId(), judgment.getRequestedAt(), input));
		}
		return targets;
	}

	/**
	 * 판정 결과로 상품의 태그와 검색 키워드를 통째로 바꾸고 요청을 완료한다.
	 * 판정하는 동안 상품명·옵션명이 바뀌었으면 결과를 버리고, 상품이 삭제됐으면 판정 행을 지운다.
	 */
	@Transactional
	public void complete(ProductTagTarget target, ProductTagScores scores) {
		Optional<ProductTagJudgment> confirmed = confirmUnchanged(target);
		if (confirmed.isEmpty()) {
			return;
		}
		ProductTagJudgment judgment = confirmed.get();
		Set<String> applied = scores.appliedTagIds();
		replaceTags(target.productId(), applied);
		// 검색 행은 상품 등록·수정과 검색 백필이 만든다. 아직 없으면 키워드를 둘 곳이 없으므로 롤백해 요청을 대기로 남기고,
		// 임대(10분)가 끝나 다시 꺼낼 때는 백필이 행을 만들었을 것이다
		if (productSearchRepository.updateKeywords(target.productId(), tagList.searchKeywords(applied), now()) == 0) {
			throw new IllegalStateException("검색 행이 없어 태그 키워드를 저장하지 못했습니다. productId=" + target.productId());
		}
		Optional<Map.Entry<String, Double>> top = scores.topTag();
		judgment.complete(scores.tagVersion(), scores.model(), top.map(Map.Entry::getKey).orElse(null),
			top.map(Map.Entry::getValue).orElse(null), now());
	}

	/**
	 * 판정 실패를 기록한다. 재시도할 실패면 다음 시도 시각을 미루고, 아니면 바로 최종 실패로 끝낸다.
	 * 판정하는 동안 상품이 바뀌었으면 바뀌기 전 내용의 실패라 세지 않는다.
	 *
	 * @param cause 판정기가 던진 예외. 마지막 오류와 로그에 남긴다
	 */
	@Transactional
	public void fail(ProductTagTarget target, RuntimeException cause, boolean retryable) {
		Optional<ProductTagJudgment> confirmed = confirmUnchanged(target);
		if (confirmed.isEmpty()) {
			return;
		}
		ProductTagJudgment judgment = confirmed.get();
		if (retryable) {
			judgment.failRetryable(cause.toString(), now());
		} else {
			judgment.fail(cause.toString());
		}

		if (judgment.getStatus() == ProductTagJudgmentStatus.FAILED) {
			// 다음 기동의 백필이 다시 대기로 넣는다. 그 전에 원인을 볼 수 있게 ERROR로 남긴다
			log.error("상품 태그 판정 최종 실패. judgmentId={}, productId={}, attemptCount={}",
				judgment.getId(), judgment.getProductId(), judgment.getAttemptCount(), cause);
		} else {
			// 다시 시도해 회복될 수 있어 스택 트레이스 없이 남긴다
			log.warn("상품 태그 판정 실패, 나중에 다시 시도. judgmentId={}, productId={}, attemptCount={}, nextAttemptAt={}, error={}",
				judgment.getId(), judgment.getProductId(), judgment.getAttemptCount(), judgment.getNextAttemptAt(),
				cause.toString());
		}
	}

	// 한 트랜잭션의 두 문장이라 둘 다 반영되거나 둘 다 롤백된다. 이미 대기인 행은 어느 문장의 조건에도 들지 않아 두 번 돌려도 같다
	@Override
	@Transactional
	public ProductTagBackfillResult backfill() {
		LocalDateTime now = now();
		int created = judgmentRepository.insertMissing(now);
		int requeued = judgmentRepository.requeueStale(tagList.version(), now);
		if (created + requeued > 0) {
			log.info("상품 태그 판정 백필 종료. createdCount={}, requeuedCount={}, tagVersion={}", created, requeued,
				tagList.version());
		}
		return new ProductTagBackfillResult(created, requeued);
	}

	@Override
	public List<UntaggedProductResult> getUntaggedProducts(UntaggedProductQuery query) {
		if (query.from().isAfter(query.to())) {
			throw new RestApiException(ProductErrorCode.INVALID_TAG_JUDGMENT_PERIOD);
		}
		return judgmentRepository.findUntagged(query.from().atStartOfDay(), query.to().plusDays(1).atStartOfDay(),
				query.limit()).stream()
			.map(row -> new UntaggedProductResult(row.productId(), row.productName(), row.judgedAt(),
				tagList.find(row.topTagId()).map(ProductTagList.Tag::label).orElse(null), row.topScore()))
			.toList();
	}

	// 판정 행을 잠근 뒤 요청 세대가 꺼낼 때와 같은지 본다. 잠그지 않으면 판매자 수정이 요청을 새 세대로 되돌린 뒤
	// 이 트랜잭션이 완료로 덮어써, 바뀐 상품명이 판정되지 않는다
	private Optional<ProductTagJudgment> confirmUnchanged(ProductTagTarget target) {
		Optional<ProductTagJudgment> judgment = judgmentRepository.findForUpdateById(target.judgmentId());
		if (judgment.isEmpty()) {
			// 다른 작업자가 상품 삭제를 보고 먼저 지웠다
			return Optional.empty();
		}
		if (!judgment.get().isRequestedAt(target.requestedAt())) {
			// 판매자 수정이 요청을 이미 새 세대의 대기로 되돌렸으므로 그대로 두고 다음 실행에서 새 내용으로 판정한다
			log.info("태그를 판정하는 동안 상품이 바뀌어 결과를 버림. judgmentId={}, productId={}", target.judgmentId(),
				target.productId());
			return Optional.empty();
		}
		if (productRepository.findByIdAndDeletedAtIsNull(target.productId()).isEmpty()) {
			removeJudgmentOfDeletedProduct(judgment.get());
			return Optional.empty();
		}
		return judgment;
	}

	// 상품 삭제는 되돌리는 기능이 없고, 삭제한 상품은 구매자 검색에도 나오지 않아 판정할 이유가 없다.
	// 상태(CANCELLED 등)를 늘리지 않고 판정 행을 지워 대기열에서 뺀다. 백필은 삭제된 상품을 넣지 않아 다시 생기지 않는다.
	// 이미 붙은 태그는 남긴다. 검색과 태그 필터가 삭제된 상품을 거르므로 지우는 일만 늘어난다
	private void removeJudgmentOfDeletedProduct(ProductTagJudgment judgment) {
		judgmentRepository.delete(judgment);
		log.info("삭제된 상품의 태그 판정 요청을 지움. judgmentId={}, productId={}", judgment.getId(), judgment.getProductId());
	}

	// 남는 태그의 행은 그대로 둔다. 지우고 새로 넣으면 Hibernate가 INSERT를 DELETE보다 먼저 내보내 (상품, 태그) UNIQUE에 걸린다
	private void replaceTags(Long productId, Set<String> applied) {
		List<ProductTag> existing = productTagRepository.findByProductId(productId);
		productTagRepository.deleteAll(existing.stream().filter(tag -> !applied.contains(tag.getTagId())).toList());
		Set<String> added = new HashSet<>(applied);
		existing.forEach(tag -> added.remove(tag.getTagId()));
		productTagRepository.saveAll(added.stream().sorted().map(tagId -> ProductTag.create(productId, tagId)).toList());
	}

	// DB가 마이크로초까지만 저장한다. 요청 세대를 같은지 비교하므로 저장 전후 값이 달라지지 않게 자른다
	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
	}
}
