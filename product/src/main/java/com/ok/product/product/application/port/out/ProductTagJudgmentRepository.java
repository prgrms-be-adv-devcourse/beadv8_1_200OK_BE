package com.ok.product.product.application.port.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import com.ok.product.product.application.dto.UntaggedProductRow;
import com.ok.product.product.domain.ProductTagJudgment;
import com.ok.product.product.domain.ProductTagJudgmentStatus;

/**
 * 상품 태그 판정 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ProductTagJudgmentRepository {

	/**
	 * 작업자가 꺼낼 요청을 오래 기다린 순서로 잠가 조회한다.
	 * 다른 작업자가 꺼내는 중인 행은 기다리지 않고 건너뛴다. 잠금은 커밋 뒤에 풀리므로 꺼낸 요청은 claim으로 미뤄야 다시 꺼내지지 않는다.
	 */
	List<ProductTagJudgment> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
		ProductTagJudgmentStatus status, LocalDateTime now, Limit limit);

	/**
	 * 판정 결과를 저장하기 전에 행을 잠가 읽는다. 잠근 뒤에 읽어야 판매자 수정이 바꾼 요청 세대를 본다.
	 */
	Optional<ProductTagJudgment> findForUpdateById(Long id);

	Optional<ProductTagJudgment> findByProductId(Long productId);

	<S extends ProductTagJudgment> S save(S judgment);

	void delete(ProductTagJudgment judgment);

	/**
	 * 상품의 판정을 처음 대기로 요청한다. 행이 없으면 만들고, 있으면 상태와 관계없이 새 세대로 되돌린다.
	 * 엔티티를 읽어 고치지 않고 한 문장으로 쓴다. 판매자 수정 트랜잭션이 먼저 읽어 둔 값으로 작업자가 저장한 판정 결과를 덮지 않게 한다.
	 */
	void request(Long productId, LocalDateTime now);

	/**
	 * 판정 행이 없는 삭제되지 않은 상품의 행을 대기로 만든다.
	 *
	 * @return 만든 행 수
	 */
	int insertMissing(LocalDateTime now);

	/**
	 * 태그 목록 버전이 {@code currentVersion}과 다른 완료 행과 최종 실패 행 중 삭제되지 않은 상품의 행을 새 세대의 대기로 되돌린다.
	 *
	 * @return 되돌린 행 수
	 */
	int requeueStale(String currentVersion, LocalDateTime now);

	/**
	 * 판정 시각이 {@code from} 이상 {@code to} 미만인 완료 행 중 태그가 0개인 삭제되지 않은 상품을 최근 판정 순으로 읽는다.
	 */
	List<UntaggedProductRow> findUntagged(LocalDateTime from, LocalDateTime to, int limit);

}
