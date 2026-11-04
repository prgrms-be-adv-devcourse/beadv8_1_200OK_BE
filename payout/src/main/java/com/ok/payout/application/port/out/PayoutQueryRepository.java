package com.ok.payout.application.port.out;

import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 정산 조회 전용 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutQueryRepository {

    // 스냅샷 대상: [from, to) 기간 미정산 후보 전체 (판매자 구분 없이), 잠긴 주문의 후보는 뺌 (#195)
    List<PayoutCandidateItem> findUnsettledCandidatesInPeriod(LocalDateTime from, LocalDateTime to);

    // 스냅샷 대상 조건(기간 안 미정산)에는 맞지만 주문이 잠겨 뺀 후보 수 (스냅샷 생성 로그용)
    // 위 조회와 같은 조건에서 잠금 조건만 뒤집어, "잠금 때문에 빠진 것"만 정확히 셈
    long countLockedUnsettledCandidatesInPeriod(LocalDateTime from, LocalDateTime to);

    // draftStep 회차마다 "이번에 처리할 판매자" 조회
    // 스냅샷에서 아직 draft가 안 된 후보가 있는 판매자 ID를 가장 먼저 들어온 후보 순으로 limit명
    List<Long> findUndraftedSellerIds(Long snapshotId, int limit);

    // draftStep 회차마다 "그 판매자들의 처리할 후보" 조회 (대금·배송비·수수료 3종 모두)
    List<PayoutCandidateItem> findUndraftedCandidates(Long snapshotId, List<Long> sellerIds);

    // 스냅샷 항목 전체 (정산후보와 조인)
    // 대상 전체를 메모리에 올리는 방식이라 createSnapshot과 같은 대량 처리 한계가 있음 (PayoutService.createSnapshot 주석 참고)
    List<SnapshotItemRow> findSnapshotItemRows(Long snapshotId);

    // 판매자 몫 draft 항목 전체 (묶인 헤더의 판매자 포함)
    // 몫 구분(systemShare)은 조회한 테이블로 정해지는 값이라 select에 상수로 넣지 않고 Java에서 채움
    List<DraftItemRow> findSellerDraftItemRows(Long snapshotId);

    // 시스템 몫 draft 항목 전체 (수수료). 헤더가 없어 headerSellerId는 null
    List<DraftItemRow> findSystemDraftItemRows(Long snapshotId);

    // 이번 스냅샷 실패 기록 전체 (verify 판정, commitStep의 결과 검증 실패 제외에 씀)
    List<FailureRow> findFailureRows(Long snapshotId);

    // 판매자 draft 헤더마다 헤더 금액과 항목 금액 합계
    // 헤더는 PayoutSellerDraft.create()가 항목을 더해 만든 값이라 정상이면 항상 같음 (다르면 draft를 만드는 코드 자체가 망가진 상태)
    // 항목이 없는 헤더도 잡으려고 헤더 기준 LEFT JOIN (항목 합계 0)
    List<SellerTotalRow> findSellerTotalRows(Long snapshotId);

    // 판매자 draft 항목 중 묶인 헤더와 스냅샷 번호가 다른 항목의 후보 ID (정상이면 0건)
    // 항목은 헤더의 번호를 복사해 만들어서 다르면 코드가 망가진 상태 (PayoutSellerDraftItem 생성자)
    // 이번 스냅샷이 헤더 쪽이든 항목 쪽이든 찾음: 헤더 기준인 commitStep과 항목 기준인 verify가 서로 다른 항목을 보게 되므로
    List<Long> findSellerDraftItemsWithMismatchedSnapshot(Long snapshotId);

    // validateStep 차감 기록 검사: 이번 스냅샷 후보 중 그 주문의 지갑 차감 기록이 없는 후보 (→ 실패로 기록되어 이번 정산에서 빠짐)
    // 이미 이번 스냅샷에 실패로 기록된 후보는 뺌 → 같은 스냅샷에 validate를 다시 실행해도 새로 저장할 게 없어 중복되지 않음
    // 주문 단위 검사라 배송비 후보(order_item_id NULL)도 order_id로 함께 걸림
    List<PayoutCandidateItem> findCandidatesWithoutDeduction(Long snapshotId);

    // validateStep 금액 검사 대상(#195): 이번 스냅샷 후보 중 그 주문의 지갑 차감 기록이 있고, 아직 이번 스냅샷에 실패로 기록되지 않은 후보
    // 차감 기록이 없는 주문은 차감 기록 검사(DEDUCTION_MISSING)가 먼저 빼므로 금액 검사를 하지 않음 (후보당 실패 기록 1건)
    // 이미 실패로 기록된 후보를 빼는 이유는 findCandidatesWithoutDeduction과 같음 (같은 스냅샷 재검증 시 중복 방지)
    List<PayoutCandidateItem> findCandidatesWithDeduction(Long snapshotId);

    // 초과 청구 검사용(#195): 이번 스냅샷에 후보가 있는 주문마다 그 주문의 정산후보 금액 합계 (주문 ID → 합계)
    // 스냅샷 밖 후보도 모두 더함: 이미 정산된 후보, 조정 후보, 스냅샷 이후 새로 들어온 후보까지
    //   이번 스냅샷 후보만 더하면, 같은 주문의 상품이 여러 날에 나눠 구매확정될 때 각 날의 합계는 작아 초과 청구를 못 잡음
    //   SQL) SELECT order_id, SUM(amount) FROM payout_candidate_items
    //        WHERE order_id IN (이번 스냅샷 후보의 order_id) GROUP BY order_id
    Map<Long, Long> sumCandidateAmountByOrderInSnapshot(Long snapshotId);

    // 초과 청구 검사용(#195): 이번 스냅샷에 후보가 있는 주문의 지갑 차감액 (주문 ID → 차감액). 차감 기록이 없는 주문은 결과에 없음
    // 차감 기록은 주문당 1건(UK order_id)이라 주문 ID가 겹치지 않음
    Map<Long, Long> findDeductionAmountByOrderInSnapshot(Long snapshotId);

    // 수수료 검사용(#195): 이번 스냅샷에 후보가 있는 주문상품의 원래(조정 아닌) 판매 대금·수수료 후보
    // 스냅샷 밖 후보도 가져옴: 대금·수수료 중 한쪽이 이미 정산됐어도 짝을 맞춰 비교해야, 남은 한쪽을 잘못 잠그지 않음
    // 조정 후보(adjustment_id > 0)는 뺌: 조정은 수수료 계산식과 상관없는 금액이라 넣으면 맞는 주문상품도 틀렸다고 봄
    // 원래 후보는 주문상품·타입당 1건 (구매확정 멱등 처리 + UK)
    // 배송비 후보는 order_item_id가 NULL이라 IN 조건에 걸리지 않음 (수수료 검사 대상 아님)
    List<PayoutCandidateItem> findOriginalSaleCandidatesInSnapshot(Long snapshotId);

    // 정산 목록 (관리자·판매자 조회). null인 조건은 걸지 않음
    // 정렬은 확정 시각 최신순 고정. 같은 시각에 확정된 정산이 여러 건이라 id를 더해 페이지를 넘겨도 순서가 흔들리지 않게 함
    Page<Payout> searchPayouts(Long payeeId, Boolean system, LocalDateTime from, LocalDateTime to,
                              Pageable pageable);

    // 정산후보 목록 (관리자·판매자 조회). sellerId가 null이면 전체 판매자
    // sellerShareOnly면 시스템 몫(수수료) 후보를 뺌 (판매자 조회용)
    // 정렬은 후보 생성 시각 최신순 고정. 같은 시각에 생긴 후보가 여러 건(대금·수수료·배송비가 함께 생김)이라 id를 더해 페이지를 넘겨도 순서가 흔들리지 않게 함
    // 정산 ID는 payout_item과 LEFT JOIN으로 가져옴 (payout_item.candidate_id가 유니크라 후보 한 건이 여러 줄로 늘지 않음)
    Page<CandidateRow> searchCandidates(Long sellerId, LocalDateTime from, LocalDateTime to,
                                         boolean unsettledOnly, boolean sellerShareOnly, Pageable pageable);

    // 정산 1건의 항목을 정산후보와 함께 페이지 단위로 조회 (항목마다 후보를 따로 조회하지 않도록 조인 1번)
    // 정산 항목은 후보를 객체 연관 없이 candidateId 숫자로만 들고 있어서 on 조건으로 조인 (FK가 있어 후보가 없는 항목은 없음)
    // 정렬은 항목 저장 순서(id) 고정
    Page<PayoutItemRow> findItemsWithCandidate(Long payoutId, Pageable pageable);

    // 정산 1건에 묶인 정산 항목 수 (상세 조회용). 항목을 다 불러오지 않고 개수만 셈
    long countItemsByPayoutId(Long payoutId);

    // 시스템 몫 정산 항목(수수료) 중 한 판매자 판매에서 나온 것의 건수·금액 합계 (정산 상세용)
    // 수수료는 판매자 정산이 아니라 같은 스냅샷의 시스템 몫 정산에 모든 판매자 것이 섞여 들어가므로 sellerId로 골라냄
    //   예) 시스템 몫 정산 14의 수수료 300(판매자 7) + 600(판매자 9) → 판매자 7이면 (1건, 300원)
    CountAndAmount summarizeSellerFeeItems(Long systemPayoutId, Long sellerId);
}
