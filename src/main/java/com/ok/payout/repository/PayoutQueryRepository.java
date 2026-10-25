package com.ok.payout.repository;

import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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

    // 스냅샷 대상: [from, to) 기간 미정산 후보 전체 (판매자 구분 없이)
    public List<PayoutCandidateItem> findUnsettledCandidatesInPeriod(LocalDateTime from, LocalDateTime to) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(unsettledInPeriod(from, to))
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
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

    // 건수·금액 합계를 함께 담는 값 (verifyStep 비교용)
    // record라 equals가 자동 생성됨 → expected.equals(drafted)로 건수와 금액이 "둘 다" 같은지 한 번에 비교
    //   예) CountAndAmount(250, 2_500_000) = 250건, 2,500,000원
    public record CountAndAmount(long count, long amount) {
        // 판매자 항목 결과 + 시스템 항목 결과를 합칠 때 사용
        //   예) (170, 2_425_000).plus(80, 75_000) → (250, 2_500_000)
        public CountAndAmount plus(CountAndAmount other) {
            return new CountAndAmount(count + other.count, amount + other.amount);
        }
    }

    // 스냅샷 목록 집계 = "원래 처리해야 할 양"
    //   SQL) SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM payout_snapshot_item WHERE snapshot_id = ?
    //   예) 250건, 2,500,000원
    // coalesce(0L): 행이 없으면 SUM이 NULL이라 0으로 바꿈 (대상 0건인 스냅샷)
    // Tuple: 두 값을 한 번에 조회한 결과. get(0) = 건수, get(1) = 금액 합계
    public CountAndAmount summarizeSnapshotItems(Long snapshotId) {
        Tuple row = queryFactory
                .select(payoutSnapshotItem.count(), payoutSnapshotItem.amount.sumLong().coalesce(0L))
                .from(payoutSnapshotItem)
                .where(payoutSnapshotItem.snapshotId.eq(snapshotId))
                .fetchOne();
        return new CountAndAmount(row.get(0, Long.class), row.get(1, Long.class));
    }

    // 판매자 몫 draft 항목 집계 = "실제로 처리한 양" 중 판매 대금·배송비
    //   예) 170건, 2,425,000원
    public CountAndAmount summarizeSellerDraftItems(Long snapshotId) {
        Tuple row = queryFactory
                .select(payoutSellerDraftItem.count(), payoutSellerDraftItem.amount.sumLong().coalesce(0L))
                .from(payoutSellerDraftItem)
                .where(payoutSellerDraftItem.snapshotId.eq(snapshotId))
                .fetchOne();
        return new CountAndAmount(row.get(0, Long.class), row.get(1, Long.class));
    }

    // 시스템 몫 draft 항목 집계 = "실제로 처리한 양" 중 수수료
    //   예) 80건, 75,000원 (수수료가 없으면 coalesce로 0건, 0원)
    public CountAndAmount summarizeSystemDraftItems(Long snapshotId) {
        Tuple row = queryFactory
                .select(payoutSystemDraftItem.count(), payoutSystemDraftItem.amount.sumLong().coalesce(0L))
                .from(payoutSystemDraftItem)
                .where(payoutSystemDraftItem.snapshotId.eq(snapshotId))
                .fetchOne();
        return new CountAndAmount(row.get(0, Long.class), row.get(1, Long.class));
    }

    // 판매자 draft 헤더(판매자별 합계)의 금액을 모두 더한 값
    // 헤더는 PayoutSellerDraft.create()가 항목을 더해 만든 값이라, 판매자 항목 금액 합계와 같아야 정상
    //   예) 판매자 A 헤더 1,000,000 + B 헤더 1,425,000 = 2,425,000 → summarizeSellerDraftItems 금액(2,425,000)과 비교
    // 다르면 draft 헤더 합계 계산 버그 (draft는 검증 실패 시에도 남는 기록이라 스스로 맞아야 함)
    // commitStep은 헤더 금액을 쓰지 않고 항목을 다시 더해 payout 금액을 만들므로, 헤더가 틀려도 장부 금액에는 번지지 않음
    public long sumSellerDraftAmount(Long snapshotId) {
        Long sum = queryFactory
                .select(payoutSellerDraft.amount.sumLong().coalesce(0L))
                .from(payoutSellerDraft)
                .where(payoutSellerDraft.snapshotId.eq(snapshotId))
                .fetchOne();
        return sum;
    }

    // 스냅샷에 있는데 draft로 옮겨지지 않은 후보 ID (검증 실패 시 원인 확인용, limit개까지)
    // draft 조회와 같은 조건(undraftedInSnapshot)을 써서 "draftStep이 놓친 것"과 정확히 같은 대상을 봄
    //   예) 스냅샷 250건 중 draft 230건 → 누락 20건의 ID [101, 207, 233, ...]
    public List<Long> findUndraftedCandidateIds(Long snapshotId, int limit) {
        return queryFactory
                .select(payoutCandidateItem.id)
                .from(payoutCandidateItem)
                .where(undraftedInSnapshot(snapshotId))
                .orderBy(payoutCandidateItem.id.asc())
                .limit(limit)
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

    // 두 draft 조회가 공유하는 조건: 이번 스냅샷 목록에 있고, 아직 draft로 옮기지 않은 후보
    // 처리한 후보는 draft 항목에 들어가 다음 회차에서 자동으로 빠지므로, 언젠가 0명이 되어 Step이 끝남
    // 한 곳에서만 정의해서 두 쿼리의 조건이 어긋나지 않게 강제함
    // (어긋나면 판매자는 뽑히는데 후보가 0건 → draft()가 계속 0보다 큰 값 반환 → tasklet 무한 반복)
    // "정산 항목(payout_item)에 없는 후보" 기준을 쓰면 안 됨: draft 결과는 아직 payout_item에 없어 같은 판매자를 반복 처리함
    private BooleanBuilder undraftedInSnapshot(Long snapshotId) {
        BooleanBuilder condition = new BooleanBuilder();
        // ① 이번 스냅샷 목록에 있는 후보만 (배치 도중 새로 생긴 후보는 제외)
        condition.and(JPAExpressions.selectOne()
                .from(payoutSnapshotItem)
                .where(payoutSnapshotItem.snapshotId.eq(snapshotId),
                        payoutSnapshotItem.candidateId.eq(payoutCandidateItem.id))
                .exists());
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
        return condition;
    }

    // 이번 스냅샷 목록에 있는 후보
    private BooleanExpression inSnapshot(Long snapshotId) {
        return JPAExpressions.selectOne()
                .from(payoutSnapshotItem)
                .where(payoutSnapshotItem.snapshotId.eq(snapshotId),
                        payoutSnapshotItem.candidateId.eq(payoutCandidateItem.id))
                .exists();
    }

    // 이번 스냅샷에서 실패로 기록되지 않은 후보 (validate 중복 방지, 3단계에서 draft 제외 조건으로도 씀)
    private BooleanExpression notFailedInSnapshot(Long snapshotId) {
        return JPAExpressions.selectOne()
                .from(payoutFailure)
                .where(payoutFailure.snapshotId.eq(snapshotId),
                        payoutFailure.candidateId.eq(payoutCandidateItem.id))
                .notExists();
    }
}
