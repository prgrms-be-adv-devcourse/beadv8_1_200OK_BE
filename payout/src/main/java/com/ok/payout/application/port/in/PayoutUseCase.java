package com.ok.payout.application.port.in;

import com.ok.payout.domain.type.PayoutRunType;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 배치 단계와 정산·정산후보 조회 입력 포트. 구현은 {@code application.service}에 있다.
 */
public interface PayoutUseCase {

    /**
     * 정산 대상 조회 상한을 구한다. 실행일에서 대기 일수를 뺀 날 00:00이다.
     */
    LocalDateTime settlementTo(LocalDate runDate);

    /**
     * 정산 대상 조회 하한을 구한다. 정산을 1건 이상 만든 마지막 정기(SCHEDULED) 완료 실행의 요청일에서 대기·여유 일수를 뺀 날 00:00이다.
     * 그런 실행이 없으면(배치 기록 삭제, 정기 실행이 정산을 만든 적 없음 등) 마지막 정산 생성일로 대체하고, 정산도 없으면 {@code null}(하한 없음)이다.
     */
    LocalDateTime settlementFrom();

    /**
     * 이번 배치의 정산 대상을 스냅샷으로 고정하고 스냅샷 ID를 반환한다. 실행 유형(정기·관리자·대기 건너뛰기)도 함께 남긴다.
     */
    Long createSnapshot(Long jobExecutionId, LocalDateTime from, LocalDateTime to, PayoutRunType runType);

    /**
     * 스냅샷 후보 중 입력이 틀린 후보를 실패로 기록하고 기록한 건수를 반환한다. 예외 없이 기록만 하므로 나머지 후보는 계속 정산된다.
     */
    int validate(Long snapshotId);

    /**
     * 스냅샷에서 판매자 {@code limit}명 단위로 임시 정산(draft)을 만들고, 이번 회차 판매자 수(실패 포함)와 실패 수를 반환한다.
     */
    PayoutDraftResult draft(Long snapshotId, int limit);

    /**
     * 검증을 통과한 draft를 payout과 payout_item으로 확정한다. 반드시 단일 트랜잭션에서 처리된다.
     */
    void commit(Long snapshotId);

    /**
     * outbox에 남은(PENDING) 정산을 Kafka로 보내고 보낸 수·실패 수·남은 수를 반환한다. 실패·중단 규칙은 구현체 참고.
     */
    PayoutPublishResult publishPending();

    /**
     * 스냅샷과 draft를 후보 단위로 비교해 어긋난 주문을 이번 정산에서 빼고 잠근다. 코드 고장이 의심되면 기록 없이 예외로 중단한다.
     */
    PayoutVerifyResult verify(Long snapshotId);

    /**
     * 정산 1건 수동 실행을 요청한다. 지금은 막아 둔 기능이라 항상 {@code FEATURE_NOT_SUPPORTED}를 던진다.
     *
     * @throws com.ok.common.exception.RestApiException 아직 열리지 않은 기능({@code FEATURE_NOT_SUPPORTED})
     */
    void runOrderItem(Long orderItemId);

    /**
     * 정산 수동 조정을 요청한다. 지금은 막아 둔 기능이라 항상 {@code FEATURE_NOT_SUPPORTED}를 던진다.
     *
     * @param adminId 요청한 관리자 ID (인증 정보에서 받은 값)
     * @throws com.ok.common.exception.RestApiException 아직 열리지 않은 기능({@code FEATURE_NOT_SUPPORTED})
     */
    void createAdjustment(Long adminId, PayoutAdjustmentCreateCommand command);

    /**
     * 관리자가 정산 목록을 조회한다.
     */
    Page<PayoutSummaryResult> searchForAdmin(PayoutSearchQuery query);

    /**
     * 판매자가 본인 정산 목록을 조회한다.
     *
     * @param sellerId 요청한 판매자의 회원 ID (토큰에서 받은 값)
     */
    Page<PayoutSummaryResult> searchForSeller(Long sellerId, PayoutSellerSearchQuery query);

    /**
     * 관리자가 정산 상세를 조회한다.
     */
    PayoutDetailResult getDetailForAdmin(Long payoutId);

    /**
     * 판매자가 본인 정산 상세를 조회한다. 본인 정산이 아니면 조회할 수 없다.
     */
    PayoutDetailResult getDetailForSeller(Long sellerId, Long payoutId);

    /**
     * 관리자가 정산의 항목 목록을 조회한다.
     */
    Page<PayoutItemResult> getItemsForAdmin(Long payoutId, PayoutItemSearchQuery query);

    /**
     * 판매자가 본인 정산의 항목 목록을 조회한다. 본인 정산이 아니면 조회할 수 없다.
     */
    Page<PayoutItemResult> getItemsForSeller(Long sellerId, Long payoutId, PayoutItemSearchQuery query);

    /**
     * 관리자가 정산후보 목록을 조회한다.
     */
    Page<PayoutCandidateResult> searchCandidatesForAdmin(PayoutCandidateSearchQuery query);

    /**
     * 판매자가 본인 정산후보 목록을 조회한다.
     */
    Page<PayoutCandidateResult> searchCandidatesForSeller(Long sellerId, PayoutSellerCandidateSearchQuery query);
}
