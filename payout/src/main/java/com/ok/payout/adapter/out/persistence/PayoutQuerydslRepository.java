package com.ok.payout.adapter.out.persistence;

import com.ok.payout.PayoutSchema;
import com.ok.payout.application.port.out.CandidateRow;
import com.ok.payout.application.port.out.CountAndAmount;
import com.ok.payout.application.port.out.DraftItemRow;
import com.ok.payout.application.port.out.FailureRow;
import com.ok.payout.application.port.out.OverchargeCandidateRow;
import com.ok.payout.application.port.out.PayoutItemRow;
import com.ok.payout.application.port.out.SellerTotalRow;
import com.ok.payout.application.port.out.SnapshotItemRow;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
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
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.ok.payout.domain.QPayout.payout;
import static com.ok.payout.domain.QPayoutCandidateItem.payoutCandidateItem;
import static com.ok.payout.domain.QPayoutFailure.payoutFailure;
import static com.ok.payout.domain.QPayoutFailureResolution.payoutFailureResolution;
import static com.ok.payout.domain.QPayoutItem.payoutItem;
import static com.ok.payout.domain.QPayoutSellerDraft.payoutSellerDraft;
import static com.ok.payout.domain.QPayoutSellerDraftItem.payoutSellerDraftItem;
import static com.ok.payout.domain.QPayoutSnapshotItem.payoutSnapshotItem;
import static com.ok.payout.domain.QPayoutSystemDraftItem.payoutSystemDraftItem;
import static com.ok.payout.domain.QPayoutWalletDeduction.payoutWalletDeduction;
import static com.ok.payout.domain.QPayoutWalletDeductionConflict.payoutWalletDeductionConflict;

/**
 * 정산 조회 전용 저장소 출력 포트({@link PayoutQueryRepository})의 QueryDSL 구현.
 * 마지막 정기 실행 요청일(#390)과 주문별 실패 실행 수(#549)는 Spring Batch 메타 테이블을 함께 읽어야 해서 EntityManager로 네이티브 SQL을 직접 실행한다.
 */
@Repository
@RequiredArgsConstructor
public class PayoutQuerydslRepository implements PayoutQueryRepository {

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    // 마지막 "정기 실행이 COMPLETED이고 정산을 만든" 배치 실행의 requestedAt (#390)
    // 네이티브 SQL인 이유: Spring Batch 메타 테이블(BATCH_JOB_EXECUTION 등)은 JPA 엔티티가 아니라 Querydsl로 볼 수 없음
    //   BATCH 테이블도 정산 스키마에 있어 이름을 붙임: monolith는 spring.batch.jdbc.table-prefix가 paldogotgan_payout.BATCH_라 기본 DB와 다르고, 단독 실행 기본 DB에서도 같은 위치임
    // run_type = SCHEDULED만 보는 이유: 대기 건너뛰기(SKIP_WAIT)의 requestedAt은 +17일 미래 값이라 하한이 되면 안 되고,
    //   관리자 실행(MANUAL)은 임시 실행이 하한을 옮기지 않게 하려는 결정으로 뺌
    // 실행 자신의 runType 잡 파라미터도 SCHEDULED여야 하는 이유: #485 이전 실행은 runType 파라미터가 없고,
    //   마이그레이션이 그 스냅샷의 run_type을 전부 SCHEDULED로 채워 놓아 이전의 대기 건너뛰기(+17일 미래) 실행이 섞여 있을 수 있음
    //   파라미터가 없는 옛 실행은 후보에서 빠져 결과가 없으면 기존 동작(폴백)으로 돌아감
    // EXISTS payout인 이유: 정산을 하나도 못 만든 실행은 하한을 옮기지 않는다는 기존 규칙("정산이 만들어졌을 때만 하한 이동")을 지키려는 것
    // 최신 판단은 requestedAt 값이 아니라 실행 ID(큰 쪽)로 함. 실행 ID는 항상 증가해 정렬이 안정적임
    // PARAMETER_VALUE는 Spring Batch가 LocalDateTime을 ISO_LOCAL_DATE_TIME 글자(예: 2026-11-10T02:00:00)로 저장한 것
    @Override
    public Optional<LocalDateTime> findLastCompletedScheduledRequestedAt() {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT p.PARAMETER_VALUE "
                                + "FROM " + PayoutSchema.NAME + ".payout_snapshot s "
                                + "JOIN " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION e ON e.JOB_EXECUTION_ID = s.job_execution_id "
                                + "JOIN " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                                + "WHERE p.PARAMETER_NAME = 'requestedAt' "
                                + "AND e.STATUS = 'COMPLETED' "
                                + "AND s.run_type = 'SCHEDULED' "
                                + "AND EXISTS (SELECT 1 FROM " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION_PARAMS rt "
                                + "WHERE rt.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                                + "AND rt.PARAMETER_NAME = 'runType' AND rt.PARAMETER_VALUE = 'SCHEDULED') "
                                + "AND EXISTS (SELECT 1 FROM " + PayoutSchema.NAME + ".payout po WHERE po.snapshot_id = s.id) "
                                + "ORDER BY e.JOB_EXECUTION_ID DESC "
                                + "LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(LocalDateTime.parse((String) rows.get(0)));
    }

    // 주문별로 "성공한 실행 중 실패 기록이 남은 실행 수" (#549)
    // 네이티브 SQL인 이유와 스키마 이름을 붙이는 이유는 findLastCompletedScheduledRequestedAt과 같음 (Spring Batch 메타 테이블을 함께 읽음)
    // COUNT(DISTINCT snapshot_id)인 이유: 스냅샷 1개 = 실행 1번이라, 한 실행에서 같은 주문의 후보 여러 건이 실패해도 1번으로 세려는 것
    // payout_item이 없는 후보의 실패만 세는 이유: 이미 정산된 후보의 실패는 더 막을 일이 아님 (unsettledInPeriod와 같은 "미정산" 기준)
    // STATUS = 'COMPLETED'만 세는 이유: FAILED로 끝난 실행의 실패 기록은 주문 탓이 아닐 수 있어 횟수에 넣지 않음
    // 해결 기록이 있는 실패는 세지 않음 (#568): 관리자가 "다시 처리"로 해결하면 횟수가 처음부터 다시 셈 (조건은 unresolvedFailure와 같은 뜻)
    // 매 실행 실패 기록 전체를 훑어 기록이 쌓일수록 느려지는 것은 알고 둠: 지금 규모에서는 문제없고, 대량에서의 시간은 #304에서 잼
    @Override
    public Map<Long, Long> countFailedRunsByOrder() {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT f.order_id, COUNT(DISTINCT f.snapshot_id) "
                                + "FROM " + PayoutSchema.NAME + ".payout_failure f "
                                + "JOIN " + PayoutSchema.NAME + ".payout_snapshot s ON s.id = f.snapshot_id "
                                + "JOIN " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION e ON e.JOB_EXECUTION_ID = s.job_execution_id "
                                + "WHERE e.STATUS = 'COMPLETED' "
                                + "AND NOT EXISTS (SELECT 1 FROM " + PayoutSchema.NAME + ".payout_item pi "
                                + "WHERE pi.candidate_id = f.candidate_id) "
                                + "AND NOT EXISTS (SELECT 1 FROM " + PayoutSchema.NAME + ".payout_failure_resolution r "
                                + "WHERE r.failure_id = f.id) "
                                + "GROUP BY f.order_id")
                .getResultList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            counts.put(((Number) columns[0]).longValue(), ((Number) columns[1]).longValue());
        }
        return counts;
    }

    // 다시 검사할 주문들의 미정산 후보 (#549): 조회 기간 하한 없이 createdAt < to
    // 하한이 없는 이유: 실패한 주문의 후보는 이전 실행의 조회 기간 안에 있었어도 기간 하한이 올라가 지금은 기간 밖이라, 하한을 두면 다시 못 잡음
    // unsettledInPeriod(null, to)를 그대로 써 미정산·상한 기준을 스냅샷 대상 조회와 같게 하고, 잠긴 주문은 같은 방식으로 뺌
    // IN 목록을 나눠 보내지 않음: 1~2번 실패한 주문만 들어와 지금 규모에서는 짧음. 잠긴 주문도 목록에 남아 길어질 수 있어 대량에서는 #304에서 봄
    @Override
    public List<PayoutCandidateItem> findUnsettledCandidatesOfOrdersBefore(Collection<Long> orderIds, LocalDateTime to) {
        // 빈 IN 목록은 DB마다 동작이 달라 질의하지 않음
        if (orderIds.isEmpty()) {
            return new ArrayList<>();
        }
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(payoutCandidateItem.orderId.in(orderIds), unsettledInPeriod(null, to), lockedOrder().not())
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 멈춘 주문 수 세기용 (#549): 위 조회와 같은 조건에서 주문 ID만 중복 없이 뽑음 (후보 엔티티를 불러오지 않음)
    // 조건을 위 조회와 따로 쓰면 둘이 어긋나 "다시 넣는 대상"과 "세는 대상"이 달라지므로 같은 조건 메서드를 그대로 씀
    @Override
    public List<Long> findOrderIdsWithUnsettledCandidatesBefore(Collection<Long> orderIds, LocalDateTime to) {
        // 빈 IN 목록은 DB마다 동작이 달라 질의하지 않음
        if (orderIds.isEmpty()) {
            return new ArrayList<>();
        }
        return queryFactory
                .select(payoutCandidateItem.orderId)
                .distinct()
                .from(payoutCandidateItem)
                .where(payoutCandidateItem.orderId.in(orderIds), unsettledInPeriod(null, to), lockedOrder().not())
                .fetch();
    }

    // 해결 기록이 있는 주문 중, 상한(to) 전 미정산 후보가 남아 있고 잠기지 않은 주문 ID (#568, 중복 없음, 조회 기간 하한 없음)
    // 해결하면 실패 횟수가 0이 되어 #549의 "1~2번 실패한 주문 다시 넣기"에 들지 않으므로, 기간 밖으로 밀린 해결 주문을 따로 고름
    // 해결 기록 쪽에서 출발함: 정산후보 표에는 주문 ID만으로 쓰는 인덱스가 없어 후보 쪽에서 출발하면 상한 전 후보를 거의 다 훑음
    //   해결 기록은 order_id 인덱스로 주문을 고르고, 후보 조회는 UK(order_id, ship_seller_id, adjustment_id)의 앞자리로 찾음
    //   이미 다 정산된 주문은 미정산 조건이 빼지만 해결 기록 자체는 계속 쌓이므로, 해결 기록이 크게 늘었을 때의 시간은 #304에서 봄
    // 해결 방법을 거르지 않는 이유: 지금은 REPROCESS(다시 처리)만 있음. "정산 안 함" 같은 방법을 더하면 여기서 REPROCESS만 골라야 함
    // 해결 뒤 다시 3번 실패해 멈춘 주문은 여기서 빼지 않음 → 부르는 쪽(PayoutService.createSnapshot)이 멈춘 주문을 뺌
    @Override
    public List<Long> findResolvedOrderIdsWithUnsettledCandidatesBefore(LocalDateTime to) {
        return queryFactory
                .select(payoutFailureResolution.orderId)
                .distinct()
                .from(payoutFailureResolution)
                .where(JPAExpressions.selectOne()
                        .from(payoutCandidateItem)
                        .where(payoutCandidateItem.orderId.eq(payoutFailureResolution.orderId),
                                unsettledInPeriod(null, to), lockedOrder().not())
                        .exists())
                .fetch();
    }

    // 그 주문의 해결되지 않은 실패 기록 (#568 해결 서비스의 저장 대상), id 오름차순
    // 사유·실행 성공 여부·정산 여부로 거르지 않음: #549 횟수는 사유를 가리지 않고 세므로 일부만 해결하면 계속 멈춰 있음
    // id 오름차순인 이유: 같은 주문을 동시에 두 번 해결할 때 두 요청이 같은 순서로 저장해, 서로를 기다리는 데드락 대신 유니크 위반(409)이 나게 함
    @Override
    public List<PayoutFailure> findUnresolvedFailuresByOrderId(Long orderId) {
        return queryFactory
                .selectFrom(payoutFailure)
                .where(payoutFailure.orderId.eq(orderId), unresolvedFailure())
                .orderBy(payoutFailure.id.asc())
                .fetch();
    }

    // 그 주문이 잠겼는지 (#568 해결 서비스의 "해결할 것이 있는지" 판정): lockedOrder와 같은 조건을 주문 ID로 봄
    @Override
    public boolean isOrderLocked(Long orderId) {
        Integer found = queryFactory
                .selectOne()
                .from(payoutFailure)
                .where(payoutFailure.orderId.eq(orderId),
                        payoutFailure.reason.in(PayoutFailureReason.orderLockingReasons()),
                        unresolvedFailure())
                .fetchFirst();
        return found != null;
    }

    // 스냅샷 대상: [from, to) 기간 미정산 후보 전체 (판매자 구분 없이), 잠긴 주문의 후보는 뺌 (#195, #552부터 잠그는 사유는 수수료 불일치·결과 검증 불일치)
    @Override
    public List<PayoutCandidateItem> findUnsettledCandidatesInPeriod(LocalDateTime from, LocalDateTime to) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(unsettledInPeriod(from, to), lockedOrder().not())
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 스냅샷 대상 조건(기간 안 미정산)에는 맞지만 주문이 잠겨 뺀 후보 수 (스냅샷 생성 로그용)
    // 위 조회와 같은 조건에서 잠금 조건만 뒤집어, "잠금 때문에 빠진 것"만 정확히 셈
    @Override
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
    @Override
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
    @Override
    public List<PayoutCandidateItem> findUndraftedCandidates(Long snapshotId, List<Long> sellerIds) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(
                        payoutCandidateItem.sellerId.in(sellerIds),
                        undraftedInSnapshot(snapshotId)) // 판매자 조회와 반드시 같은 조건
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 스냅샷 항목 전체 (정산후보와 조인)
    // 대상 전체를 메모리에 올리는 방식이라 createSnapshot과 같은 대량 처리 한계가 있음 (PayoutService.createSnapshot 주석 참고)
    @Override
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
    @Override
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
                // 헤더의 스냅샷도 같아야 함: 어긋난 항목은 앞의 코드 고장 검사(findSellerDraftItemsWithMismatchedSnapshot)에서 이미 멈추지만,
                //   이 조회만 따로 쓰일 때도 commitStep(헤더 기준)과 같은 항목을 보게 함
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
    @Override
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
    @Override
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
    @Override
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

    // 판매자 draft 항목 중 묶인 헤더와 스냅샷 번호가 다른 항목의 후보 ID (정상이면 0건)
    // 항목은 헤더의 번호를 복사해 만들어서 다르면 코드가 망가진 상태 (PayoutSellerDraftItem 생성자)
    // 이번 스냅샷이 헤더 쪽이든 항목 쪽이든 찾음: 헤더 기준인 commitStep과 항목 기준인 verify가 서로 다른 항목을 보게 되므로
    @Override
    public List<Long> findSellerDraftItemsWithMismatchedSnapshot(Long snapshotId) {
        return queryFactory
                .select(payoutSellerDraftItem.candidateId)
                .from(payoutSellerDraftItem)
                .join(payoutSellerDraftItem.sellerDraft, payoutSellerDraft)
                .where(payoutSellerDraftItem.snapshotId.eq(snapshotId).or(payoutSellerDraft.snapshotId.eq(snapshotId)),
                        payoutSellerDraftItem.snapshotId.ne(payoutSellerDraft.snapshotId))
                .orderBy(payoutSellerDraftItem.candidateId.asc())
                .fetch();
    }

    // validateStep 차감 기록 검사: 이번 스냅샷 후보 중 그 주문의 지갑 차감 기록이 없는 후보 (→ 실패로 기록되어 이번 정산에서 빠짐)
    // 이미 이번 스냅샷에 실패로 기록된 후보는 뺌 → 같은 스냅샷에 validate를 다시 실행해도 새로 저장할 게 없어 중복되지 않음
    // 주문 단위 검사라 배송비 후보(order_item_id NULL)도 order_id로 함께 걸림
    @Override
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
    @Override
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
    // 스냅샷 밖 후보도 모두 더함: 이미 정산된 후보, 스냅샷 이후 새로 들어온 후보까지
    //   이번 스냅샷 후보만 더하면, 같은 주문의 상품이 여러 날에 나눠 구매확정될 때 각 날의 합계는 작아 초과 청구를 못 잡음
    // 조정 후보(adjustment_id > 0)는 뺌(#552): 넣으면 −조정이 초과 청구를 가려 틀린 금액이 통과됨
    //   예) 결제 30,000, 원래 후보 40,000(10,000 초과), −10,000 조정 → 합계 30,000이라 통과됐음. 이제 40,000 > 30,000으로 걸림
    //   #552 전에는 잠금이 이 위험을 가렸음(잠긴 주문엔 조정이 와도 스냅샷에 안 들어감). 잠금을 없애며 함께 막음
    //   조정으로 금액 불일치를 바로잡는 흐름은 #291에서 다시 설계 (수수료 검사는 이미 조정을 뺌, findOriginalSaleCandidatesInSnapshot)
    //   SQL) SELECT order_id, SUM(amount) FROM payout_candidate_items
    //        WHERE order_id IN (이번 스냅샷 후보의 order_id) AND adjustment_id = 0 GROUP BY order_id
    @Override
    public Map<Long, Long> sumCandidateAmountByOrderInSnapshot(Long snapshotId) {
        List<Tuple> rows = queryFactory
                .select(payoutCandidateItem.orderId, payoutCandidateItem.amount.sumLong())
                .from(payoutCandidateItem)
                .where(payoutCandidateItem.orderId.in(orderIdsInSnapshot(snapshotId)),
                        payoutCandidateItem.adjustmentId.eq(PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID))
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
    @Override
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
    @Override
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

    // 초과 청구 근거용(#391): 주문들의 정산후보 전체를 생성 순서로, 이미 정산됐는지와 함께
    // 조정 후보는 뺌(#552): 판정 합계(sumCandidateAmountByOrderInSnapshot)와 같은 범위여야 근거의 누적합이 합계와 맞음
    //   SQL) SELECT c.*, pi.id FROM payout_candidate_items c LEFT JOIN payout_item pi ON pi.candidate_id = c.id
    //        WHERE c.order_id IN (...) AND c.adjustment_id = 0 ORDER BY c.created_at, c.id
    // 정산 여부는 select에 조건식을 두지 않고 payout_item id를 읽어 Java에서 null 여부로 정함 (JPQL select의 조건식 지원에 기대지 않으려고)
    @Override
    public List<OverchargeCandidateRow> findOverchargeCandidates(Collection<Long> orderIds) {
        List<Tuple> rows = queryFactory
                .select(payoutCandidateItem.id,
                        payoutCandidateItem.orderId,
                        payoutCandidateItem.orderItemId,
                        payoutCandidateItem.sellerId,
                        payoutCandidateItem.amount,
                        payoutCandidateItem.createdAt,
                        payoutItem.id)
                .from(payoutCandidateItem)
                .leftJoin(payoutItem).on(payoutItem.candidateId.eq(payoutCandidateItem.id))
                .where(payoutCandidateItem.orderId.in(orderIds),
                        payoutCandidateItem.adjustmentId.eq(PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID))
                .orderBy(payoutCandidateItem.createdAt.asc(), payoutCandidateItem.id.asc())
                .fetch();
        List<OverchargeCandidateRow> result = new ArrayList<>();
        for (Tuple row : rows) {
            result.add(new OverchargeCandidateRow(
                    row.get(payoutCandidateItem.id),
                    row.get(payoutCandidateItem.orderId),
                    row.get(payoutCandidateItem.orderItemId),
                    row.get(payoutCandidateItem.sellerId),
                    row.get(payoutCandidateItem.amount),
                    row.get(payoutCandidateItem.createdAt),
                    row.get(payoutItem.id) != null));
        }
        return result;
    }

    // 초과 청구 근거용(#391): 주문들의 지갑 금액 충돌에서 받은 금액 (주문 ID → 받은 금액 목록, 기록 순서)
    @Override
    public Map<Long, List<Long>> findReceivedConflictAmountsByOrder(Collection<Long> orderIds) {
        List<Tuple> rows = queryFactory
                .select(payoutWalletDeductionConflict.orderId, payoutWalletDeductionConflict.receivedAmount)
                .from(payoutWalletDeductionConflict)
                .where(payoutWalletDeductionConflict.orderId.in(orderIds))
                .orderBy(payoutWalletDeductionConflict.id.asc())
                .fetch();
        Map<Long, List<Long>> amounts = new HashMap<>();
        for (Tuple row : rows) {
            Long orderId = row.get(payoutWalletDeductionConflict.orderId);
            if (!amounts.containsKey(orderId)) {
                amounts.put(orderId, new ArrayList<>());
            }
            amounts.get(orderId).add(row.get(payoutWalletDeductionConflict.receivedAmount));
        }
        return amounts;
    }

    // 정산 목록 (관리자·판매자 조회). null인 조건은 걸지 않음
    // 정렬은 확정 시각 최신순 고정. 같은 시각에 확정된 정산이 여러 건이라 id를 더해 페이지를 넘겨도 순서가 흔들리지 않게 함
    @Override
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

    // 정산후보 목록 (관리자·판매자 조회). sellerId가 null이면 전체 판매자
    // sellerShareOnly면 시스템 몫(수수료) 후보를 뺌 (판매자 조회용)
    // 정렬은 후보 생성 시각 최신순 고정. 같은 시각에 생긴 후보가 여러 건(대금·수수료·배송비가 함께 생김)이라 id를 더해 페이지를 넘겨도 순서가 흔들리지 않게 함
    // 정산 ID는 payout_item과 LEFT JOIN으로 가져옴 (payout_item.candidate_id가 유니크라 후보 한 건이 여러 줄로 늘지 않음)
    @Override
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

    // 정산 1건의 항목을 정산후보와 함께 페이지 단위로 조회 (항목마다 후보를 따로 조회하지 않도록 조인 1번)
    // 정산 항목은 후보를 객체 연관 없이 candidateId 숫자로만 들고 있어서 on 조건으로 조인 (FK가 있어 후보가 없는 항목은 없음)
    // 정렬은 항목 저장 순서(id) 고정
    @Override
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
    @Override
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
    @Override
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

    // 후보의 주문이 잠겼는지: 그 주문에 해결되지 않은 잠그는 사유(PayoutFailureReason.locksOrder)의 실패 기록이 하나라도 있으면 잠김
    // 해결 기록이 있는 실패는 잠그지 않음 (#568): 관리자가 "다시 처리"로 해결하면 다음 스냅샷부터 다시 들어옴
    // 스냅샷 번호와 상관없이 주문 단위로 봄 → 잠긴 뒤 새로 들어온 같은 주문의 후보(나중에 구매확정된 상품, 조정 등)도 빠짐
    // 잠그는 사유: 수수료 불일치(validate, #195·#391), 결과 검증 불일치(verify, #290). 초과 청구(#195)는 #552부터 잠그지 않고 다시 검사함
    // 잠금을 남긴 Job이 이후 Step에서 FAILED로 끝나도 실패 기록은 남아 잠금이 유지됨
    //   (수수료 불일치는 입력에 대한 판정이라 재실행해도 다시 잠기고, 결과 검증 불일치는 관리자 확인 전까지 막아 두려는 것이라
    //    실패한 Job의 판정을 지울 이유가 없음)
    private BooleanExpression lockedOrder() {
        return JPAExpressions.selectOne()
                .from(payoutFailure)
                .where(payoutFailure.orderId.eq(payoutCandidateItem.orderId),
                        payoutFailure.reason.in(PayoutFailureReason.orderLockingReasons()),
                        unresolvedFailure())
                .exists();
    }

    // 실패 기록(payoutFailure)에 해결 기록이 없는지 (#568)
    // 여러 스냅샷에 걸쳐 실패 기록을 읽는 조건(잠금 lockedOrder·isOrderLocked, 해결 서비스의 저장 대상)에만 씀
    //   실패 횟수(countFailedRunsByOrder)는 네이티브 SQL이라 같은 조건(r.failure_id = f.id)을 SQL에 따로 씀
    // 한 스냅샷 안에서 빼는 조건(notFailedInSnapshot, findFailureRows)에는 쓰지 않음:
    //   배치가 도는 중에 해결해도 그 실행에서 이미 실패한 후보는 그 실행의 draft·확정에서 계속 빠져야 함 (해결은 다음 실행부터)
    private BooleanExpression unresolvedFailure() {
        return JPAExpressions.selectOne()
                .from(payoutFailureResolution)
                .where(payoutFailureResolution.failureId.eq(payoutFailure.id))
                .notExists();
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
