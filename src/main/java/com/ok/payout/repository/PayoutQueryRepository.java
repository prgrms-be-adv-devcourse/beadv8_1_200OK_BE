package com.ok.payout.repository;

import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.domain.QPayoutCandidateItem;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLSubQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ok.payout.domain.QPayout.payout;
import static com.ok.payout.domain.QPayoutCandidateItem.payoutCandidateItem;
import static com.ok.payout.domain.QPayoutFailure.payoutFailure;
import static com.ok.payout.domain.QPayoutItem.payoutItem;
import static com.ok.payout.domain.QPayoutSellerDraft.payoutSellerDraft;
import static com.ok.payout.domain.QPayoutSellerDraftItem.payoutSellerDraftItem;
import static com.ok.payout.domain.QPayoutSnapshotItem.payoutSnapshotItem;
import static com.ok.payout.domain.QPayoutSystemDraftItem.payoutSystemDraftItem;
import static com.ok.payout.domain.QPayoutWalletDeduction.payoutWalletDeduction;

@Repository
@RequiredArgsConstructor
public class PayoutQueryRepository {

    private final JPAQueryFactory queryFactory;

    // 스냅샷 대상: [from, to) 기간 미정산 후보 전체 (판매자 구분 없이), 잠긴 주문의 후보는 뺌 (#195)
    public List<PayoutCandidateItem> findUnsettledCandidatesInPeriod(LocalDateTime from, LocalDateTime to) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(unsettledInPeriod(from, to), lockedOrder().not())
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 스냅샷 대상 조건(기간 안 미정산)에는 맞지만 주문이 잠겨 뺀 후보 수 (스냅샷 생성 로그용)
    // 위 조회와 같은 조건에서 잠금 조건만 뒤집어, "잠금 때문에 빠진 것"만 정확히 셈
    public long countLockedUnsettledCandidatesInPeriod(LocalDateTime from, LocalDateTime to) {
        Long count = queryFactory
                .select(payoutCandidateItem.count())
                .from(payoutCandidateItem)
                .where(unsettledInPeriod(from, to), lockedOrder())
                .fetchOne();
        return Objects.requireNonNullElse(count, 0L);
    }

    // draftStep 회차마다 "이번에 처리할 판매자" 조회
    // 스냅샷에서 아직 draft가 안 된 후보가 있는 판매자 ID를 가장 먼저 들어온 후보 순으로 limit명
    public List<Long> findUndraftedSellerIds(Long snapshotId, int limit) {
        return queryFactory
                .select(payoutCandidateItem.sellerId)
                .from(payoutCandidateItem)
                .where(undraftedInSnapshot(snapshotId))
                .groupBy(payoutCandidateItem.sellerId)
                .orderBy(payoutCandidateItem.createdAt.min().asc(),
                        payoutCandidateItem.sellerId.asc())
                .limit(limit)
                .fetch();
    }

    // draftStep 회차마다 "그 판매자들의 처리할 후보" 조회 (대금·배송비·수수료 3종 모두)
    public List<PayoutCandidateItem> findUndraftedCandidates(Long snapshotId, List<Long> sellerIds) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(
                        payoutCandidateItem.sellerId.in(sellerIds),
                        undraftedInSnapshot(snapshotId)) // 판매자 조회와 반드시 같은 조건
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 건수·금액 합계를 함께 담는 값 (정산 상세의 판매자 수수료 집계)
    //   예) CountAndAmount(2, 900) = 2건, 900원
    public record CountAndAmount(long count, long amount) {
    }

    // ---------- verifyStep 결과 검증 (#290) ----------
    // 후보 단위로 비교하려고 스냅샷·draft·실패 기록을 한 줄씩 가져옴 (판정은 PayoutDraftValidator)
    // 서비스 쪽 타입을 리포지토리가 쓰면 레이어 규칙에 걸려서, 조회 결과용 record를 여기에 둠 (CandidateRow와 같은 이유)

    // 스냅샷 항목 한 줄 = "원래 처리해야 할 후보". 금액은 스냅샷 시점 값, 주문·판매자·타입은 정산후보에서 가져옴
    public record SnapshotItemRow(Long candidateId, Long orderId, Long sellerId, PayoutEventType eventType, Long amount) {
    }

    // draft 항목 한 줄 (판매자 몫·시스템 몫 공통)
    //   headerSellerId: 판매자 몫이면 그 항목이 묶인 판매자 draft(헤더)의 판매자, 시스템 몫이면 null
    //                   commitStep은 헤더의 판매자로 정산 수취인을 정하므로, 항목의 판매자와 다르면 돈이 다른 판매자에게 감
    //   systemShare: 시스템 몫 테이블에서 왔으면 true
    public record DraftItemRow(Long candidateId, Long orderId, Long sellerId, Long headerSellerId,
                               PayoutEventType eventType, Long amount, boolean systemShare) {
    }

    // 이번 스냅샷 실패 기록 한 줄
    public record FailureRow(Long candidateId, Long orderId, PayoutFailureReason reason) {
    }

    // 판매자 draft 헤더 1건의 금액과 그 헤더에 묶인 항목 금액 합계
    public record SellerTotalRow(Long sellerId, Long headerAmount, Long itemAmount) {
    }

    // 스냅샷 항목 전체 (정산후보와 조인)
    // 대상 전체를 메모리에 올리는 방식이라 createSnapshot과 같은 대량 처리 한계가 있음 (PayoutService.createSnapshot 주석 참고)
    public List<SnapshotItemRow> findSnapshotItemRows(Long snapshotId) {
        return queryFactory
                .select(Projections.constructor(SnapshotItemRow.class,
                        payoutSnapshotItem.candidateId,
                        payoutCandidateItem.orderId,
                        payoutCandidateItem.sellerId,
                        payoutCandidateItem.eventType,
                        payoutSnapshotItem.amount))
                .from(payoutSnapshotItem)
                .join(payoutCandidateItem).on(payoutCandidateItem.id.eq(payoutSnapshotItem.candidateId))
                .where(payoutSnapshotItem.snapshotId.eq(snapshotId))
                .orderBy(payoutSnapshotItem.candidateId.asc())
                .fetch();
    }

    // 판매자 몫 draft 항목 전체 (묶인 헤더의 판매자 포함)
    // 몫 구분(systemShare)은 조회한 테이블로 정해지는 값이라 select에 상수로 넣지 않고 Java에서 채움
    public List<DraftItemRow> findSellerDraftItemRows(Long snapshotId) {
        List<Tuple> rows = queryFactory
                .select(payoutSellerDraftItem.candidateId,
                        payoutSellerDraftItem.orderId,
                        payoutSellerDraftItem.sellerId,
                        payoutSellerDraft.sellerId,
                        payoutSellerDraftItem.eventType,
                        payoutSellerDraftItem.amount)
                .from(payoutSellerDraftItem)
                .join(payoutSellerDraftItem.sellerDraft, payoutSellerDraft)
                // 헤더의 스냅샷도 같아야 함: commitStep은 헤더(스냅샷 기준) 아래 항목을 정산하므로, 헤더가 다른 스냅샷인 항목은 이번에 정산되지 않음
                //   → 여기서도 빼서 "draft 누락"으로 잡히게 함 (verify가 통과시킨 항목이 commit에서 조용히 빠지는 일이 없게)
                .where(payoutSellerDraftItem.snapshotId.eq(snapshotId),
                        payoutSellerDraft.snapshotId.eq(snapshotId))
                .orderBy(payoutSellerDraftItem.candidateId.asc())
                .fetch();
        List<DraftItemRow> items = new ArrayList<>();
        for (Tuple row : rows) {
            items.add(new DraftItemRow(row.get(payoutSellerDraftItem.candidateId), row.get(payoutSellerDraftItem.orderId),
                    row.get(payoutSellerDraftItem.sellerId), row.get(payoutSellerDraft.sellerId),
                    row.get(payoutSellerDraftItem.eventType), row.get(payoutSellerDraftItem.amount), false));
        }
        return items;
    }

    // 시스템 몫 draft 항목 전체 (수수료). 헤더가 없어 headerSellerId는 null
    public List<DraftItemRow> findSystemDraftItemRows(Long snapshotId) {
        List<PayoutSystemDraftItem> rows = queryFactory
                .selectFrom(payoutSystemDraftItem)
                .where(payoutSystemDraftItem.snapshotId.eq(snapshotId))
                .orderBy(payoutSystemDraftItem.candidateId.asc())
                .fetch();
        List<DraftItemRow> items = new ArrayList<>();
        for (PayoutSystemDraftItem row : rows) {
            items.add(new DraftItemRow(row.getCandidateId(), row.getOrderId(), row.getSellerId(), null,
                    row.getEventType(), row.getAmount(), true));
        }
        return items;
    }

    // 이번 스냅샷 실패 기록 전체 (verify 판정, commitStep의 결과 검증 실패 제외에 씀)
    public List<FailureRow> findFailureRows(Long snapshotId) {
        return queryFactory
                .select(Projections.constructor(FailureRow.class,
                        payoutFailure.candidateId,
                        payoutFailure.orderId,
                        payoutFailure.reason))
                .from(payoutFailure)
                .where(payoutFailure.snapshotId.eq(snapshotId))
                .orderBy(payoutFailure.candidateId.asc())
                .fetch();
    }

    // 판매자 draft 헤더마다 헤더 금액과 항목 금액 합계
    // 헤더는 PayoutSellerDraft.create()가 항목을 더해 만든 값이라 정상이면 항상 같음 (다르면 draft를 만드는 코드 자체가 망가진 상태)
    // 항목이 없는 헤더도 잡으려고 헤더 기준 LEFT JOIN (항목 합계 0)
    public List<SellerTotalRow> findSellerTotalRows(Long snapshotId) {
        return queryFactory
                .select(Projections.constructor(SellerTotalRow.class,
                        payoutSellerDraft.sellerId,
                        payoutSellerDraft.amount,
                        payoutSellerDraftItem.amount.sumLong().coalesce(0L)))
                .from(payoutSellerDraft)
                .leftJoin(payoutSellerDraftItem).on(payoutSellerDraftItem.sellerDraft.id.eq(payoutSellerDraft.id))
                .where(payoutSellerDraft.snapshotId.eq(snapshotId))
                .groupBy(payoutSellerDraft.id, payoutSellerDraft.sellerId, payoutSellerDraft.amount)
                .orderBy(payoutSellerDraft.sellerId.asc())
                .fetch();
    }

    // validateStep 차감 기록 검사: 이번 스냅샷 후보 중 그 주문의 지갑 차감 기록이 없는 후보 (→ 실패로 기록되어 이번 정산에서 빠짐)
    // 이미 이번 스냅샷에 실패로 기록된 후보는 뺌 → 같은 스냅샷에 validate를 다시 실행해도 새로 저장할 게 없어 중복되지 않음
    // 주문 단위 검사라 배송비 후보(order_item_id NULL)도 order_id로 함께 걸림
    public List<PayoutCandidateItem> findCandidatesWithoutDeduction(Long snapshotId) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(
                        inSnapshot(snapshotId),
                        JPAExpressions.selectOne()
                                .from(payoutWalletDeduction)
                                .where(payoutWalletDeduction.orderId.eq(payoutCandidateItem.orderId))
                                .notExists(),
                        notFailedInSnapshot(snapshotId))
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // validateStep 금액 검사 대상(#195): 이번 스냅샷 후보 중 그 주문의 지갑 차감 기록이 있고, 아직 이번 스냅샷에 실패로 기록되지 않은 후보
    // 차감 기록이 없는 주문은 차감 기록 검사(DEDUCTION_MISSING)가 먼저 빼므로 금액 검사를 하지 않음 (후보당 실패 기록 1건)
    // 이미 실패로 기록된 후보를 빼는 이유는 findCandidatesWithoutDeduction과 같음 (같은 스냅샷 재검증 시 중복 방지)
    public List<PayoutCandidateItem> findCandidatesWithDeduction(Long snapshotId) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(
                        inSnapshot(snapshotId),
                        JPAExpressions.selectOne()
                                .from(payoutWalletDeduction)
                                .where(payoutWalletDeduction.orderId.eq(payoutCandidateItem.orderId))
                                .exists(),
                        notFailedInSnapshot(snapshotId))
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 초과 청구 검사용(#195): 이번 스냅샷에 후보가 있는 주문마다 그 주문의 정산후보 금액 합계 (주문 ID → 합계)
    // 스냅샷 밖 후보도 모두 더함: 이미 정산된 후보, 조정 후보, 스냅샷 이후 새로 들어온 후보까지
    //   이번 스냅샷 후보만 더하면, 같은 주문의 상품이 여러 날에 나눠 구매확정될 때 각 날의 합계는 작아 초과 청구를 못 잡음
    //   SQL) SELECT order_id, SUM(amount) FROM payout_candidate_items
    //        WHERE order_id IN (이번 스냅샷 후보의 order_id) GROUP BY order_id
    public Map<Long, Long> sumCandidateAmountByOrderInSnapshot(Long snapshotId) {
        List<Tuple> rows = queryFactory
                .select(payoutCandidateItem.orderId, payoutCandidateItem.amount.sumLong())
                .from(payoutCandidateItem)
                .where(payoutCandidateItem.orderId.in(orderIdsInSnapshot(snapshotId)))
                .groupBy(payoutCandidateItem.orderId)
                .fetch();
        Map<Long, Long> sums = new HashMap<>();
        for (Tuple row : rows) {
            sums.put(row.get(0, Long.class), row.get(1, Long.class));
        }
        return sums;
    }

    // 초과 청구 검사용(#195): 이번 스냅샷에 후보가 있는 주문의 지갑 차감액 (주문 ID → 차감액). 차감 기록이 없는 주문은 결과에 없음
    // 차감 기록은 주문당 1건(UK order_id)이라 주문 ID가 겹치지 않음
    public Map<Long, Long> findDeductionAmountByOrderInSnapshot(Long snapshotId) {
        List<Tuple> rows = queryFactory
                .select(payoutWalletDeduction.orderId, payoutWalletDeduction.amount)
                .from(payoutWalletDeduction)
                .where(payoutWalletDeduction.orderId.in(orderIdsInSnapshot(snapshotId)))
                .fetch();
        Map<Long, Long> amounts = new HashMap<>();
        for (Tuple row : rows) {
            amounts.put(row.get(0, Long.class), row.get(1, Long.class));
        }
        return amounts;
    }

    // 수수료 검사용(#195): 이번 스냅샷에 후보가 있는 주문상품의 원래(조정 아닌) 판매 대금·수수료 후보
    // 스냅샷 밖 후보도 가져옴: 대금·수수료 중 한쪽이 이미 정산됐어도 짝을 맞춰 비교해야, 남은 한쪽을 잘못 잠그지 않음
    // 조정 후보(adjustment_id > 0)는 뺌: 조정은 수수료 계산식과 상관없는 금액이라 넣으면 맞는 주문상품도 틀렸다고 봄
    // 원래 후보는 주문상품·타입당 1건 (구매확정 멱등 처리 + UK)
    // 배송비 후보는 order_item_id가 NULL이라 IN 조건에 걸리지 않음 (수수료 검사 대상 아님)
    public List<PayoutCandidateItem> findOriginalSaleCandidatesInSnapshot(Long snapshotId) {
        QPayoutCandidateItem snapshotCandidate = new QPayoutCandidateItem("snapshotCandidate");
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(
                        payoutCandidateItem.adjustmentId.eq(PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID),
                        payoutCandidateItem.eventType.in(PayoutEventType.SALE_AMOUNT, PayoutEventType.SALE_FEE),
                        payoutCandidateItem.orderItemId.in(JPAExpressions
                                .select(snapshotCandidate.orderItemId)
                                .from(payoutSnapshotItem)
                                .join(snapshotCandidate).on(snapshotCandidate.id.eq(payoutSnapshotItem.candidateId))
                                .where(payoutSnapshotItem.snapshotId.eq(snapshotId))))
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 정산 목록 (관리자·판매자 조회). null인 조건은 걸지 않음
    // 정렬은 확정 시각 최신순 고정. 같은 시각에 확정된 정산이 여러 건이라 id를 더해 페이지를 넘겨도 순서가 흔들리지 않게 함
    public Page<Payout> searchPayouts(Long payeeId, Boolean system, LocalDateTime from, LocalDateTime to,
                                      Pageable pageable) {
        BooleanBuilder condition = payoutSearchCondition(payeeId, system, from, to);
        List<Payout> content = queryFactory
                .selectFrom(payout)
                .where(condition)
                .orderBy(payout.createdAt.desc(), payout.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> Objects.requireNonNullElse(queryFactory
                .select(payout.count())
                .from(payout)
                .where(condition)
                .fetchOne(), 0L));
    }

    // 정산후보 한 줄 + 정산됐으면 그 정산 ID (정산후보 조회용)
    // 서비스 쪽 타입(service/dto)을 리포지토리가 쓰면 레이어 규칙에 걸려서, 리포지토리 안에 조회 결과용 record를 둠 (PayoutItemRow와 같은 이유)
    public record CandidateRow(
            Long candidateId,
            PayoutEventType eventType,
            Long orderId,
            Long orderItemId,                  // 배송비 후보는 null
            Long sellerId,
            Long amount,
            LocalDateTime candidateCreatedAt,  // 평소 건은 구매확정 직후, 조정 건은 조정한 시각
            // 0이면 평소 건, 1 이상이면 관리자 조정. Querydsl이 생성자를 타입으로 찾아서 엔티티 필드와 같은 Long으로 둠
            Long adjustmentId,
            Long adminId,                      // 조정 건일 때만 값
            String reason,                     // 조정 건일 때만 값
            Long payoutId                      // 정산된 후보면 그 정산 ID, 아직 정산 전이면 null
    ) {
    }

    // 정산후보 목록 (관리자·판매자 조회). sellerId가 null이면 전체 판매자
    // sellerShareOnly면 시스템 몫(수수료) 후보를 뺌 (판매자 조회용)
    // 정렬은 후보 생성 시각 최신순 고정. 같은 시각에 생긴 후보가 여러 건(대금·수수료·배송비가 함께 생김)이라 id를 더해 페이지를 넘겨도 순서가 흔들리지 않게 함
    // 정산 ID는 payout_item과 LEFT JOIN으로 가져옴 (payout_item.candidate_id가 유니크라 후보 한 건이 여러 줄로 늘지 않음)
    public Page<CandidateRow> searchCandidates(Long sellerId, LocalDateTime from, LocalDateTime to,
                                               boolean unsettledOnly, boolean sellerShareOnly, Pageable pageable) {
        BooleanBuilder condition = candidateSearchCondition(sellerId, from, to, unsettledOnly, sellerShareOnly);
        List<CandidateRow> content = queryFactory
                .select(Projections.constructor(CandidateRow.class,
                        payoutCandidateItem.id,
                        payoutCandidateItem.eventType,
                        payoutCandidateItem.orderId,
                        payoutCandidateItem.orderItemId,
                        payoutCandidateItem.sellerId,
                        payoutCandidateItem.amount,
                        payoutCandidateItem.createdAt,
                        payoutCandidateItem.adjustmentId,
                        payoutCandidateItem.adminId,
                        payoutCandidateItem.reason,
                        payoutItem.payout.id))
                .from(payoutCandidateItem)
                .leftJoin(payoutItem).on(payoutItem.candidateId.eq(payoutCandidateItem.id))
                .where(condition)
                .orderBy(payoutCandidateItem.createdAt.desc(), payoutCandidateItem.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // 개수는 조인 없이 셈: 조건(미정산 여부 포함)은 후보 테이블만으로 판단되고, 조인해도 줄 수가 같음
        return PageableExecutionUtils.getPage(content, pageable, () -> Objects.requireNonNullElse(queryFactory
                .select(payoutCandidateItem.count())
                .from(payoutCandidateItem)
                .where(condition)
                .fetchOne(), 0L));
    }

    // 정산 항목 한 줄 + 그 항목의 정산후보 정보 (항목 조회용)
    // 서비스 쪽 타입(service/dto)을 리포지토리가 쓰면 레이어 규칙에 걸려서, 리포지토리 안에 조회 결과용 record를 둠
    public record PayoutItemRow(
            Long payoutItemId,
            Long candidateId,
            PayoutEventType eventType,
            Long orderId,
            Long orderItemId,                  // 배송비 항목은 null
            Long sellerId,
            Long amount,
            LocalDateTime candidateCreatedAt,  // 정산후보 생성 시각
            // 0이면 평소 건, 1 이상이면 관리자 조정. 엔티티 필드와 같은 Long으로 둠 (Querydsl이 생성자를 타입으로 찾아서 long이면 못 찾음)
            Long adjustmentId,
            Long adminId,                      // 조정 건일 때만 값
            String reason                      // 조정 건일 때만 값
    ) {
    }

    // 정산 1건의 항목을 정산후보와 함께 페이지 단위로 조회 (항목마다 후보를 따로 조회하지 않도록 조인 1번)
    // 정산 항목은 후보를 객체 연관 없이 candidateId 숫자로만 들고 있어서 on 조건으로 조인 (FK가 있어 후보가 없는 항목은 없음)
    // 정렬은 항목 저장 순서(id) 고정
    public Page<PayoutItemRow> findItemsWithCandidate(Long payoutId, Pageable pageable) {
        List<PayoutItemRow> content = queryFactory
                .select(Projections.constructor(PayoutItemRow.class,
                        payoutItem.id,
                        payoutItem.candidateId,
                        payoutItem.eventType,
                        payoutItem.orderId,
                        payoutItem.orderItemId,
                        payoutItem.sellerId,
                        payoutItem.amount,
                        payoutCandidateItem.createdAt,
                        payoutCandidateItem.adjustmentId,
                        payoutCandidateItem.adminId,
                        payoutCandidateItem.reason))
                .from(payoutItem)
                .join(payoutCandidateItem).on(payoutCandidateItem.id.eq(payoutItem.candidateId))
                .where(payoutItem.payout.id.eq(payoutId))
                .orderBy(payoutItem.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> countItemsByPayoutId(payoutId));
    }

    // 정산 1건에 묶인 정산 항목 수 (상세 조회용). 항목을 다 불러오지 않고 개수만 셈
    public long countItemsByPayoutId(Long payoutId) {
        Long count = queryFactory
                .select(payoutItem.count())
                .from(payoutItem)
                .where(payoutItem.payout.id.eq(payoutId))
                .fetchOne();
        return Objects.requireNonNullElse(count, 0L);
    }

    // 시스템 몫 정산 항목(수수료) 중 한 판매자 판매에서 나온 것의 건수·금액 합계 (정산 상세용)
    // 수수료는 판매자 정산이 아니라 같은 스냅샷의 시스템 몫 정산에 모든 판매자 것이 섞여 들어가므로 sellerId로 골라냄
    //   예) 시스템 몫 정산 14의 수수료 300(판매자 7) + 600(판매자 9) → 판매자 7이면 (1건, 300원)
    public CountAndAmount summarizeSellerFeeItems(Long systemPayoutId, Long sellerId) {
        Tuple row = queryFactory
                .select(payoutItem.count(), payoutItem.amount.sumLong().coalesce(0L))
                .from(payoutItem)
                .where(payoutItem.payout.id.eq(systemPayoutId),
                        payoutItem.sellerId.eq(sellerId))
                .fetchOne();
        return new CountAndAmount(row.get(0, Long.class), row.get(1, Long.class));
    }

    // 정산 목록 조건. 기간은 스냅샷 기간과 같은 규칙: from 포함, to 미포함
    private BooleanBuilder payoutSearchCondition(Long payeeId, Boolean system, LocalDateTime from, LocalDateTime to) {
        BooleanBuilder condition = new BooleanBuilder();
        if (payeeId != null) {
            condition.and(payout.payeeId.eq(payeeId));
        }
        if (system != null) {
            condition.and(payout.system.eq(system));
        }
        if (from != null) {
            condition.and(payout.createdAt.goe(from));
        }
        if (to != null) {
            condition.and(payout.createdAt.lt(to));
        }
        return condition;
    }

    // 정산후보 목록 조건. 기간은 정산 목록과 같은 규칙: from 포함, to 미포함
    // 미정산 판단은 정산 배치(unsettledInPeriod)와 같은 기준: payout_item에 같은 candidate_id가 없으면 미정산
    private BooleanBuilder candidateSearchCondition(Long sellerId, LocalDateTime from, LocalDateTime to,
                                                    boolean unsettledOnly, boolean sellerShareOnly) {
        BooleanBuilder condition = new BooleanBuilder();
        if (sellerId != null) {
            condition.and(payoutCandidateItem.sellerId.eq(sellerId));
        }
        condition.and(payoutCandidateItem.createdAt.goe(from));
        condition.and(payoutCandidateItem.createdAt.lt(to));
        if (unsettledOnly) {
            condition.and(JPAExpressions.selectOne()
                    .from(payoutItem)
                    .where(payoutItem.candidateId.eq(payoutCandidateItem.id))
                    .notExists());
        }
        if (sellerShareOnly) {
            // 시스템 몫 판단은 PayoutCandidateItem.isSystemShare()와 같은 기준 (SALE_FEE만 시스템 몫). 이벤트 타입이 추가되면 함께 수정
            condition.and(payoutCandidateItem.eventType.ne(PayoutEventType.SALE_FEE));
        }
        return condition;
    }

    // 스냅샷 대상 조건: [from, to) 기간 안 + 아직 정산 안 된 후보 (from이 null이면 하한 없음)
    private BooleanBuilder unsettledInPeriod(LocalDateTime from, LocalDateTime to) {
        BooleanBuilder condition = new BooleanBuilder();
        if (from != null) {
            condition.and(payoutCandidateItem.createdAt.goe(from));
        }
        condition.and(payoutCandidateItem.createdAt.lt(to));
        // payout_item에 같은 candidate_id가 없으면 미정산
        // (정산후보 단위로 판단해야, 원래 후보가 정산된 뒤 생긴 같은 주문상품·같은 타입의 조정 후보도 정산됨)
        condition.and(JPAExpressions.selectOne()
                .from(payoutItem)
                .where(payoutItem.candidateId.eq(payoutCandidateItem.id))
                .notExists());
        return condition;
    }

    // 두 draft 조회가 공유하는 조건: 이번 스냅샷 목록에 있고, 아직 draft로 옮기지 않았고, 실패로 기록되지 않은 후보
    // 처리한 후보는 draft 항목에 들어가 다음 회차에서 자동으로 빠지므로, 언젠가 0명이 되어 Step이 끝남
    // 한 곳에서만 정의해서 두 쿼리의 조건이 어긋나지 않게 강제함
    // (어긋나면 판매자는 뽑히는데 후보가 0건 → draft()가 계속 0보다 큰 값 반환 → tasklet 무한 반복)
    // "정산 항목(payout_item)에 없는 후보" 기준을 쓰면 안 됨: draft 결과는 아직 payout_item에 없어 같은 판매자를 반복 처리함
    private BooleanBuilder undraftedInSnapshot(Long snapshotId) {
        BooleanBuilder condition = new BooleanBuilder();
        // ① 이번 스냅샷 목록에 있는 후보만 (배치 도중 새로 생긴 후보는 제외)
        condition.and(inSnapshot(snapshotId));
        // ② 아직 판매자 draft 항목으로 옮기지 않은 것
        condition.and(JPAExpressions.selectOne()
                .from(payoutSellerDraftItem)
                .where(payoutSellerDraftItem.snapshotId.eq(snapshotId),
                        payoutSellerDraftItem.candidateId.eq(payoutCandidateItem.id))
                .notExists());
        // ③ 아직 시스템 draft 항목으로 옮기지 않은 것
        condition.and(JPAExpressions.selectOne()
                .from(payoutSystemDraftItem)
                .where(payoutSystemDraftItem.snapshotId.eq(snapshotId),
                        payoutSystemDraftItem.candidateId.eq(payoutCandidateItem.id))
                .notExists());
        // ④ 이번 스냅샷에서 실패로 기록되지 않은 것 (실패 = 이번 정산에서 빠짐. 실패 정보는 payout_failure에만 둠)
        //    validate 실패뿐 아니라 이후 생길 실패(#90 draft 예외)도 모두 여기서 빠져, 같은 후보를 다시 잡는 무한 반복이 생기지 않음
        condition.and(notFailedInSnapshot(snapshotId));
        return condition;
    }

    // 후보의 주문이 잠겼는지: 그 주문에 잠그는 사유(PayoutFailureReason.locksOrder)의 실패 기록이 하나라도 있으면 잠김
    // 스냅샷 번호와 상관없이 주문 단위로 봄 → 잠긴 뒤 새로 들어온 같은 주문의 후보(나중에 구매확정된 상품, 조정 등)도 빠짐
    // 잠그는 사유: 금액 검사 불일치(validate, #195), 결과 검증 불일치(verify, #290)
    // 잠금을 남긴 Job이 이후 Step에서 FAILED로 끝나도 실패 기록은 남아 잠금이 유지됨
    //   (금액 불일치는 입력에 대한 판정이라 재실행해도 다시 잠기고, 결과 검증 불일치는 관리자 확인 전까지 막아 두려는 것이라
    //    실패한 Job의 판정을 지울 이유가 없음)
    private BooleanExpression lockedOrder() {
        return JPAExpressions.selectOne()
                .from(payoutFailure)
                .where(payoutFailure.orderId.eq(payoutCandidateItem.orderId),
                        payoutFailure.reason.in(PayoutFailureReason.orderLockingReasons()))
                .exists();
    }

    // 이번 스냅샷 후보들의 주문 ID (금액 검사 집계 쿼리의 IN 조건)
    // 바깥 쿼리와 같은 정산후보 테이블을 다시 읽으므로 별칭(snapshotCandidate)을 따로 둠
    private JPQLSubQuery<Long> orderIdsInSnapshot(Long snapshotId) {
        QPayoutCandidateItem snapshotCandidate = new QPayoutCandidateItem("snapshotCandidate");
        return JPAExpressions
                .select(snapshotCandidate.orderId)
                .from(payoutSnapshotItem)
                .join(snapshotCandidate).on(snapshotCandidate.id.eq(payoutSnapshotItem.candidateId))
                .where(payoutSnapshotItem.snapshotId.eq(snapshotId));
    }

    // 이번 스냅샷 목록에 있는 후보
    private BooleanExpression inSnapshot(Long snapshotId) {
        return JPAExpressions.selectOne()
                .from(payoutSnapshotItem)
                .where(payoutSnapshotItem.snapshotId.eq(snapshotId),
                        payoutSnapshotItem.candidateId.eq(payoutCandidateItem.id))
                .exists();
    }

    // 이번 스냅샷에서 실패로 기록되지 않은 후보 (validate 중복 방지, draft 대상 조건 ④)
    private BooleanExpression notFailedInSnapshot(Long snapshotId) {
        return JPAExpressions.selectOne()
                .from(payoutFailure)
                .where(payoutFailure.snapshotId.eq(snapshotId),
                        payoutFailure.candidateId.eq(payoutCandidateItem.id))
                .notExists();
    }
}
