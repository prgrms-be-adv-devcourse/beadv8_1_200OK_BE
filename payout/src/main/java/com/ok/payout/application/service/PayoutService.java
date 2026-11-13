package com.ok.payout.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutOutbox;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSellerDraftItem;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSnapshotItem;
import com.ok.payout.domain.type.PayoutRunType;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.application.port.out.PayoutCandidateItemRepository;
import com.ok.payout.application.port.out.PayoutFailureRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.application.port.out.CountAndAmount;
import com.ok.payout.application.port.out.DraftItemRow;
import com.ok.payout.application.port.out.FailureRow;
import com.ok.payout.application.port.out.OverchargeCandidateRow;
import com.ok.payout.application.port.out.SnapshotItemRow;
import com.ok.payout.application.port.out.PayoutRepository;
import com.ok.payout.application.port.out.PayoutSellerDraftRepository;
import com.ok.payout.application.port.out.PayoutSnapshotItemRepository;
import com.ok.payout.application.port.out.PayoutSnapshotRepository;
import com.ok.payout.application.port.out.PayoutSystemDraftItemRepository;
import com.ok.payout.application.port.in.PayoutAdjustmentCreateCommand;
import com.ok.payout.application.port.in.PayoutCandidateResult;
import com.ok.payout.application.port.in.PayoutCandidateSearchQuery;
import com.ok.payout.application.port.in.PayoutDetailResult;
import com.ok.payout.application.port.in.PayoutDraftResult;
import com.ok.payout.application.port.in.PayoutItemResult;
import com.ok.payout.application.port.in.PayoutItemSearchQuery;
import com.ok.payout.application.port.in.PayoutSearchQuery;
import com.ok.payout.application.port.in.PayoutSellerCandidateSearchQuery;
import com.ok.payout.application.port.in.PayoutSellerSearchQuery;
import com.ok.payout.application.port.in.PayoutSummaryResult;
import com.ok.payout.application.port.in.PayoutPublishResult;
import com.ok.payout.application.port.in.PayoutVerifyResult;
import com.ok.payout.application.port.out.SellerPayoutCreated;
import com.ok.payout.application.port.in.PayoutUseCase;
import com.ok.payout.application.port.out.PayoutEventPublisher;
import com.ok.payout.application.port.out.PayoutModuleEventPublisher;
import com.ok.payout.application.port.out.PayoutOutboxRepository;
import com.ok.payout.application.port.out.PayoutOutboxRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService implements PayoutUseCase {

    // 코드 고장으로 전체를 멈출 때 예외 메시지에 담는 항목(판매자 합계 불일치 내용, 후보 ID) 최대 개수 (메시지가 너무 길어지지 않게, 나머지는 건수로만 남김)
    private static final int MESSAGE_ITEM_LIMIT = 20;
    private static final int MAX_CONSECUTIVE_PUBLISH_FAILURES = 3;

    private final PayoutQueryRepository payoutQueryRepository;
    private final PayoutCandidateItemRepository payoutCandidateItemRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutSnapshotRepository payoutSnapshotRepository;
    private final PayoutSnapshotItemRepository payoutSnapshotItemRepository;
    private final PayoutSellerDraftRepository payoutSellerDraftRepository;
    private final PayoutSystemDraftItemRepository payoutSystemDraftItemRepository;
    private final PayoutFailureRepository payoutFailureRepository;
    // draftStep의 판매자 1명 저장·실패 기록 (판매자마다 별도 트랜잭션, #90)
    private final PayoutSellerDraftService payoutSellerDraftService;
    private final PayoutEventPublisher payoutEventPublisher;
    private final PayoutModuleEventPublisher payoutModuleEventPublisher;
    private final PayoutOutboxRepository payoutOutboxRepository;
    private final PayoutOutboxService payoutOutboxService;

    // 조회 상한: 실행일 − 대기 일수 00:00 (생성 후 대기 일수가 지난 후보만 정산)
    @Override
    public LocalDateTime settlementTo(LocalDate runDate) {
        return runDate.minusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS).atStartOfDay();
    }

    // 조회 하한: 기준일 − (대기 일수 + 여유 일수) 00:00, 기준일이 없으면 null(하한 없음)
    // 기준일 = 정산을 1건 이상 만든 마지막 정기(SCHEDULED) 완료 실행의 요청일
    //  - 수동(MANUAL) 실행은 제외: 임의로 돌린 실행이 하한을 움직이지 않게 함
    //  - 대기 건너뛰기(SKIP_WAIT)는 제외: 요청일이 +17일 미래라 하한이 앞으로 튐
    //  - 정산 0건 실행은 제외: 정산이 만들어졌을 때만 하한이 움직이게 함
    //  - 요청일은 settlementTo가 상한을 계산하는 기준과 같아서, 하한 = 지난 상한 − 여유 일수가 됨
    // 위 조건에 맞는 정기 실행이 없으면 마지막 정산 생성일로 대체함. 없애 버리면 하한이 통째로 사라지기 때문
    //  - 배치 기록이 지워진 경우, 정산 기록이 지워져 실행 기록만 고아로 남은 경우
    //  - 수동·대기 건너뛰기 실행만 정산을 만들었거나, 정기 실행이 전부 정산 0건이었던 경우
    // 기준이 마지막 실행이라 배치가 밀리면 하한도 그만큼 과거로 내려가 밀린 후보까지 조회됨
    @Override
    public LocalDateTime settlementFrom() {
        LocalDate baseDate;
        Optional<LocalDateTime> lastScheduledRequestedAt = payoutQueryRepository.findLastCompletedScheduledRequestedAt();
        if (lastScheduledRequestedAt.isPresent()) {
            baseDate = lastScheduledRequestedAt.get().toLocalDate();
        } else {
            Optional<Payout> lastPayout = payoutRepository.findFirstByOrderByIdDesc();
            if (lastPayout.isEmpty()) {
                return null;
            }
            baseDate = lastPayout.get().getCreatedAt().toLocalDate();
        }
        return baseDate
                .minusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS + PayoutPolicy.SETTLEMENT_LOOKBACK_MARGIN_DAYS)
                .atStartOfDay();
    }

    // 이번 배치의 정산 대상을 스냅샷으로 고정하고 스냅샷 ID를 반환
    // 헤더와 목록을 한 트랜잭션에서 만들어, 중간에 실패하면 둘 다 남지 않음 (반쪽짜리 스냅샷 방지)
    // 이후 Step은 이 목록만 보고 처리하므로, 배치 도중 새로 생긴 정산후보는 다음 배치로 넘어감
    // 잠긴 주문(수수료 불일치 #195·#391, 결과 검증 #290)의 후보는 스냅샷에 넣지 않음 → 실패 기록도 다시 생기지 않고 정산 대상에서 계속 빠짐 (초과 청구는 #552부터 잠그지 않음)
    // 성공한 실행에서 PayoutPolicy.MAX_FAILED_RUNS번 실패한 주문도 스냅샷에서 뺌(멈춤), 1번 이상 그 미만이면 조회 기간 밖이어도 다시 넣음 (#549)
    //
    // 지금은 팀 규칙(QueryDSL 조회 + JPA 저장)을 따라 대상을 Java로 불러와 저장함
    // 대상 전체를 메모리에 올리고, ID가 IDENTITY라 batch insert가 안 돼 건수만큼 INSERT가 나감
    // → 하루 정산후보가 수만 건을 넘어 메모리·시간이 부담되면 HQL INSERT ... SELECT(DB 안에서 1문장 복사)로 바꾸는 것을 고려
    @Override
    @Transactional
    public Long createSnapshot(Long jobExecutionId, LocalDateTime from, LocalDateTime to, PayoutRunType runType) {
        PayoutSnapshot snapshot = payoutSnapshotRepository.save(PayoutSnapshot.create(jobExecutionId, from, to, runType));

        // 주문별 "성공한 실행에서 실패한 횟수"(#549)로 주문을 나눔: MAX_FAILED_RUNS번 이상이면 멈춘 주문, 1번 이상 그 미만이면 다시 넣을 주문
        Map<Long, Long> failedRunCounts = payoutQueryRepository.countFailedRunsByOrder();
        Set<Long> stoppedOrderIds = new HashSet<>();
        List<Long> retryOrderIds = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : failedRunCounts.entrySet()) {
            if (entry.getValue() >= PayoutPolicy.MAX_FAILED_RUNS) {
                stoppedOrderIds.add(entry.getKey());
            } else {
                retryOrderIds.add(entry.getKey());
            }
        }

        // 1) 기간 안 후보에서 멈춘 주문의 후보를 뺌: 주문 단위로 멈추므로 그 주문의 다른 후보(스스로는 실패하지 않은 것, 뒤늦게 들어온 것)도 같이 빠짐
        //    이미 정산된 후보(다른 판매자가 통과한 것 등)는 미정산 조회에 안 나와 영향이 없음
        List<PayoutCandidateItem> candidates = new ArrayList<>();
        Set<Long> candidateIds = new HashSet<>();
        for (PayoutCandidateItem candidate : payoutQueryRepository.findUnsettledCandidatesInPeriod(from, to)) {
            if (stoppedOrderIds.contains(candidate.getOrderId())) {
                continue;
            }
            candidates.add(candidate);
            candidateIds.add(candidate.getId());
        }

        // 2) 아직 멈추지 않은 실패 주문(1~2번)의 후보를 조회 기간 하한 없이 다시 넣음
        //    조회 기간 하한은 정기 실행마다 올라가서, 실패한 주문의 후보가 기간 밖으로 밀려 영영 못 잡히는 것을 막으려는 것 (상한 to는 그대로)
        //    잠긴 주문은 이 조회가 이미 빼므로 다시 들어오지 않음
        //    1)에서 이미 들어온 후보는 ID로 걸러 한 번만 넣음
        for (PayoutCandidateItem candidate : payoutQueryRepository.findUnsettledCandidatesOfOrdersBefore(retryOrderIds, to)) {
            if (candidateIds.add(candidate.getId())) {
                candidates.add(candidate);
            }
        }
        // 두 조회가 각각 ID 오름차순이라 합친 뒤 다시 정렬: 스냅샷 항목이 후보 ID 순으로 들어가도록 (기존 동작과 같음)
        candidates.sort(Comparator.comparing(PayoutCandidateItem::getId));

        List<PayoutSnapshotItem> items = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidates) {
            items.add(PayoutSnapshotItem.create(snapshot.getId(), candidate));
        }
        payoutSnapshotItemRepository.saveAll(items);

        // 팀 로그 규칙(service.md "로그"): INFO는 업무상 의미 있는 사건(예: 배치 종료)에 남기고, 식별자는 key={} 형태로 넣음
        // 스냅샷 생성은 배치가 맡은 대상이 정해지는 시점이라 INFO로 남김
        // jobExecutionId로 배치 실행 기록과, snapshotId로 이후 Step 로그(결과 검증으로 뺀 주문 등)와 이어서 볼 수 있음
        log.info("정산 스냅샷 생성. snapshotId={}, jobExecutionId={}, 대상 건수={}", snapshot.getId(), jobExecutionId, items.size());

        // 이번 조회 기간에서 잠긴 주문(수수료 불일치 #195·#391, 결과 검증 #290) 때문에 빠진 후보가 있는 날만 남김: 잠금 때문에 정산되지 않은 후보가 있다는 것을 로그로 바로 알 수 있게
        // (0건이면 매일 같은 로그가 남아 생략)
        // 조회 기간 안의 후보만 세므로 누적 잠금 건수가 아님: 기간이 지난 후보는 다음 날부터 이 수에서 빠짐 (누적은 실패 기록의 잠금 사유로 확인)
        // 멈춘 주문이 있는 날만 WARN: MAX_FAILED_RUNS번 실패해 정산에서 빠진 주문은 사람이 확인해야 하므로 INFO보다 높게 남김 (0건이면 생략)
        // 세는 대상: 멈춘 주문 중 잠기지 않았고, 상한(to) 전의 미정산 후보가 남아 있는 주문 (같은 주문의 후보가 여러 건이어도 1건)
        //   기간 안 후보만 세면, 기간 밖에서 다시 넣어져 3번째로 실패한 주문은 멈춰도 WARN이 한 번도 안 남기 때문에 기간과 상관없이 셈 (#549 완료 조건 2)
        //   대가: 멈춘 주문이 #301로 해결되기 전까지는 매 실행마다 같은 주문이 다시 세어짐 (사용자가 감수하기로 함)
        //   이미 정산된 후보와 잠긴 주문은 이 조회가 빼므로 세지 않음 (잠긴 주문은 아래 잠금 로그가 따로 알림)
        // 실패를 저장하는 시점이 아니라 스냅샷을 만들 때 남기는 이유: 실행이 FAILED로 끝나면 그 실패는 횟수에 안 세므로, 저장 시점에는 멈춤이 확정이 아님
        //   후보 엔티티 대신 주문 ID만 중복 없이 받음: 멈춘 주문이 쌓여도 매 실행 불러오는 양이 주문 수만큼으로 그침
        List<Long> reportedStoppedOrderIds = payoutQueryRepository.findOrderIdsWithUnsettledCandidatesBefore(stoppedOrderIds, to);
        if (!reportedStoppedOrderIds.isEmpty()) {
            log.warn("{}번 실패한 주문이라 스냅샷에서 제외. snapshotId={}, 제외 주문 수={}",
                    PayoutPolicy.MAX_FAILED_RUNS, snapshot.getId(), reportedStoppedOrderIds.size());
        }
        long lockedCount = payoutQueryRepository.countLockedUnsettledCandidatesInPeriod(from, to);
        if (lockedCount > 0) {
            log.info("잠긴 주문이라 스냅샷에서 제외. snapshotId={}, 제외 후보 수={}", snapshot.getId(), lockedCount);
        }
        return snapshot.getId();
    }

    // 입력 검증(validateStep): 이번 스냅샷 후보 중 입력이 틀린 후보를 실패로 기록하고, 기록한 건수를 반환
    // 실패로 기록된 후보는 이번 정산에서 빠짐 (draftStep이 실패 기록을 보고 제외)
    // 예외를 던지지 않고 기록만 함 → 틀린 주문만 빠지고 나머지는 계속 정산됨
    // 검사 순서: 차감 기록 없음(#194) → 금액 검사(초과 청구 → 수수료, #195). 앞 검사에 걸린 후보는 뒤 검사 대상에서 빠짐 (후보당 실패 1건)
    // 모든 검사 결과를 모아 한 번에 저장: 같은 트랜잭션이라 중간에 실패하면 이번 검증의 기록이 하나도 남지 않음
    //
    // 초과 청구에 걸린 주문은 잠그지 않음(#552): 다음 실행에서 다시 검사하고, 횟수에 세어 MAX_FAILED_RUNS번이면 멈춤(#549)
    //   입력이 그대로면 매번 같은 검사에 걸려 돈이 나가지 않음. 수수료 불일치는 다시 검사해도 풀릴 길이 없어 잠금 유지(사람이 #301에서 풂)
    @Override
    @Transactional
    public int validate(Long snapshotId) {
        List<PayoutFailure> failures = new ArrayList<>();

        // 1) 차감 기록 없음 (잠그지 않음, 다음 배치에서 다시 검사)
        List<PayoutCandidateItem> missing = payoutQueryRepository.findCandidatesWithoutDeduction(snapshotId);
        for (PayoutCandidateItem candidate : missing) {
            failures.add(PayoutFailure.of(snapshotId, candidate, PayoutFailureReason.DEDUCTION_MISSING));
        }

        // 2) 금액 검사: 차감 기록이 있는 주문만 대상 (판정은 PayoutAmountValidator, 여기는 조회·저장만)
        // 대상 전체를 메모리에 올리는 방식이라 createSnapshot과 같은 대량 처리 한계가 있음 (createSnapshot 주석 참고)
        // 1)과 2)의 조회가 같은 시점의 데이터를 본다는 전제: MySQL 기본 격리 수준(REPEATABLE READ)이라 한 트랜잭션 안의 조회는 같은 스냅샷을 읽음
        //   READ COMMITTED로 바꾸면 1)과 2) 사이에 들어온 차감 기록 때문에 같은 후보가 두 번 실패로 잡혀 UK 위반 → 매 배치 실패할 수 있음
        List<PayoutCandidateItem> targets = payoutQueryRepository.findCandidatesWithDeduction(snapshotId);
        Map<Long, Long> totals = payoutQueryRepository.sumCandidateAmountByOrderInSnapshot(snapshotId);
        Map<Long, Long> deductions = payoutQueryRepository.findDeductionAmountByOrderInSnapshot(snapshotId);
        List<PayoutCandidateItem> originals = payoutQueryRepository.findOriginalSaleCandidatesInSnapshot(snapshotId);

        // 초과 청구 근거(#391): 초과 청구에 걸리는 주문만 후보 전체·지갑 금액 충돌을 다시 읽음 (걸린 주문이 없으면 조회하지 않음)
        //   같은 트랜잭션(REPEATABLE READ)이라 위 합계 조회와 같은 시점의 데이터를 봄 → 근거의 누적합이 합계와 맞음
        //   판정 공식은 PayoutAmountValidator.overchargedOrderIds 한곳에 둬서, 여기서 고른 주문과 validate가 초과 청구로 보는 주문이 같음
        Set<Long> overchargedOrderIds = PayoutAmountValidator.overchargedOrderIds(totals, deductions);
        List<OverchargeCandidateRow> overchargeCandidates = List.of();
        Map<Long, List<Long>> conflictAmounts = Map.of();
        if (!overchargedOrderIds.isEmpty()) {
            overchargeCandidates = payoutQueryRepository.findOverchargeCandidates(overchargedOrderIds);
            conflictAmounts = payoutQueryRepository.findReceivedConflictAmountsByOrder(overchargedOrderIds);
        }

        List<PayoutFailure> amountFailures = PayoutAmountValidator.validate(snapshotId, new PayoutAmountValidator.Input(
                targets, totals, deductions, originals, overchargeCandidates, conflictAmounts));
        failures.addAll(amountFailures);

        payoutFailureRepository.saveAll(failures);

        logAmountCheckFailedOrders(snapshotId, amountFailures);
        logOverchargedOrderItems(snapshotId, amountFailures, overchargeCandidates);
        logFeeMismatches(amountFailures);
        // 팀 로그 규칙(service.md "로그"): 업무상 의미 있는 사건이라 INFO. 빠진 후보가 있는 날을 로그로 바로 찾을 수 있게 함
        log.info("정산 입력 검증. snapshotId={}, 차감 기록 없어 제외한 후보 수={}, 금액 검사로 제외한 후보 수={}",
                snapshotId, missing.size(), amountFailures.size());
        return failures.size();
    }

    // 초과 청구로 실패를 기록한 주문마다 주문상품 전체 목록을 WARN (#391)
    // 실패 상세는 255자라 주문상품이 많으면 잘림 → 원인을 찾을 때 볼 전체 목록(주문상품별 합계, 배송비 포함)은 로그에 남김
    // 이번 스냅샷 후보가 모두 이미 실패로 기록돼 새 실패가 없는 주문(같은 스냅샷 재검증)은 남기지 않음
    private void logOverchargedOrderItems(Long snapshotId, List<PayoutFailure> amountFailures,
                                          List<OverchargeCandidateRow> overchargeCandidates) {
        Set<Long> overchargedOrderIds = new LinkedHashSet<>();
        for (PayoutFailure failure : amountFailures) {
            if (failure.getReason() == PayoutFailureReason.AMOUNT_MISMATCH) {
                overchargedOrderIds.add(failure.getOrderId());
            }
        }
        for (Long orderId : overchargedOrderIds) {
            List<OverchargeCandidateRow> orderRows = new ArrayList<>();
            for (OverchargeCandidateRow row : overchargeCandidates) {
                if (row.orderId().equals(orderId)) {
                    orderRows.add(row);
                }
            }
            log.warn("정산 초과 청구 주문상품 목록. snapshotId={}, orderId={}, 주문상품별 합계={}",
                    snapshotId, orderId, PayoutAmountValidator.amountsByOrderItem(orderRows));
        }
    }

    // 수수료 불일치(FEE_MISMATCH)로 기록한 주문상품마다 ERROR 1번 (#391)
    // ERROR로 남기는 이유(사용자 결정): 수수료 불일치는 정상 경로에서는 생기지 않아 시스템 문제일 가능성이 커서, 알림(#302 슬랙 전송)으로 바로 알리려고 함
    //   팀 로그 규칙(service.md "로그")은 ERROR를 "처리되지 못한 예외"로 정해 두었는데, 여기는 예외 없이 실패로 기록하고 계속 진행하는 경우라 규칙과 다름
    //   (정산 모듈 규칙 payout/CLAUDE.md "팀 규칙과 다르게 쓰는 것"에 예외로 적음. #302에서 알림을 붙일 때 이 로그 모양을 그대로 찾도록 메시지 모양을 고정함)
    // 같은 주문상품의 대금·수수료 두 후보가 함께 실패하므로, 주문상품 ID로 한 번만 남김 (같은 문제로 알림이 두 번 가지 않게)
    private void logFeeMismatches(List<PayoutFailure> amountFailures) {
        Set<Long> loggedOrderItemIds = new LinkedHashSet<>();
        for (PayoutFailure failure : amountFailures) {
            if (failure.getReason() != PayoutFailureReason.FEE_MISMATCH) {
                continue;
            }
            if (loggedOrderItemIds.add(failure.getOrderItemId())) {
                log.error("[정산-시스템문제] 수수료 불일치 orderItemId={}", failure.getOrderItemId());
            }
        }
    }

    // 금액 검사에 걸린 주문마다 WARN (#552 전에는 "정산 주문 잠금"): 이번 실행에서 그 주문이 빠졌고 왜 빠졌는지를 그날 바로 알 수 있게
    //   문구를 바꾼 이유: #552부터 초과 청구는 잠그지 않고(3번이면 멈춤, #549) 수수료 불일치만 잠가, "잠금"이라고 쓰면 초과 청구에서 틀림
    // 판매자 수를 남기는 이유: 한 판매자의 금액이 틀려도 같은 주문의 다른 판매자 후보까지 함께 빠져, 영향받은 판매자를 바로 알 수 있게
    // 금액 검사 실패 목록은 주문별로 이어서 들어옴 (PayoutAmountValidator가 주문 단위로 만듦)
    private void logAmountCheckFailedOrders(Long snapshotId, List<PayoutFailure> amountFailures) {
        Map<Long, List<PayoutFailure>> failuresByOrder = new LinkedHashMap<>();
        for (PayoutFailure failure : amountFailures) {
            if (!failuresByOrder.containsKey(failure.getOrderId())) {
                failuresByOrder.put(failure.getOrderId(), new ArrayList<>());
            }
            failuresByOrder.get(failure.getOrderId()).add(failure);
        }

        for (Map.Entry<Long, List<PayoutFailure>> entry : failuresByOrder.entrySet()) {
            Set<Long> sellerIds = new HashSet<>();
            String cause = null;
            for (PayoutFailure failure : entry.getValue()) {
                sellerIds.add(failure.getSellerId());
                // 사유는 원인 후보(초과 청구 AMOUNT_MISMATCH, 수수료 FEE_MISMATCH)의 상세로 남김
                // 함께 보류된 ORDER_HELD만 빼고 고름: 금액 검사가 만드는 사유 중 원인이 아닌 것은 ORDER_HELD뿐이라,
                //   원인 사유를 하나씩 이름으로 고르면(#391에서 FEE_MISMATCH를 나눌 때처럼) 새 원인 사유를 놓쳐 사유=null이 됨
                //   #552 전에는 locksOrder로 골랐으나, 초과 청구가 잠그지 않게 되어 locksOrder로는 초과 청구를 원인으로 고르지 못함
                if (cause == null && failure.getReason() != PayoutFailureReason.ORDER_HELD) {
                    cause = failure.getDetail();
                }
            }
            log.warn("정산 금액 검사로 주문 제외. snapshotId={}, orderId={}, 사유={}, 판매자 수={}, 후보 수={}",
                    snapshotId, entry.getKey(), cause, sellerIds.size(), entry.getValue().size());
        }
    }

    // 스냅샷에서 판매자 limit명 단위로 임시 정산(draft)을 만들고, 이번 회차 판매자 수(실패 포함)와 실패 수를 반환
    // 판매자 수가 0이면 더 없음 → tasklet 종료
    // 판매 대금·배송비는 판매자별 draft로, 수수료는 시스템 draft 항목으로만 저장 (합계는 commitStep에서 스냅샷 전체를 합침)
    // payout 생성·지갑 이벤트(commitStep)와 Kafka 발송(publishStep)은 검증 이후 단계에서 함
    //
    // 판매자 단위 격리(#90): 판매자마다 저장 트랜잭션을 따로 둠 (PayoutSellerDraftService, REQUIRES_NEW)
    //   한 판매자에서 예외가 나면 그 판매자 것만 롤백하고, 주문별로 다시 저장해 본 뒤(#534, retryByOrder) 실패한 주문의 후보만 UNEXPECTED_ERROR로 기록하고 다음 판매자로 넘어감
    //   → 같은 회차의 다른 판매자와 이후 회차는 계속 처리되고, 일부만 실패하면 Job도 멈추지 않음 (전에는 회차 전체가 롤백되고 Job이 FAILED)
    //   실패로 기록된 후보는 다음 회차의 조회 조건(조건 ④)에서 빠지므로, 실패한 판매자를 다시 뽑는 무한 반복은 없음
    //   실패한 주문은 판매자 몫과 수수료가 함께 롤백되어 commitStep에서 자연히 빠짐 (판매자 정산·시스템 몫 수수료 모두 없음)
    // 판매자 수에는 실패한 판매자도 셈: 0이 아니면 tasklet이 다음 회차를 부르고, 남은 판매자가 없을 때 0이 됨
    // 실패 수는 따로 돌려줘 배치 실행 기록(write skip count)에 남김: 일부 판매자가 실패해도 Job은 COMPLETED라 로그 말고도 볼 곳이 있어야 함
    //   일부만 실패하면 Job은 COMPLETED → 실패 수·WARN 로그로 확인 (반복 실패 알림은 #52)
    //   일부 주문만 실패한 판매자는 실패 수에 들어가지 않음(#534): 그 주문은 WARN 로그와 payout_failure로만 확인
    //   전원 실패(성공 0명)면 draft Step이 끝날 때 Job을 FAILED로 만듦 (PayoutJobConfig.failIfEverySellerFailed)
    //
    // 이 메서드에서 쓰는 것이 없음: 저장은 판매자마다 별도 트랜잭션에서 커밋되므로 이 메서드 자체는 조회만 함
    // readOnly는 단독으로 불릴 때(서비스 테스트 등)만 적용됨. draftStep에서는 Step 트랜잭션(읽기·쓰기)에 참여해 그 설정을 따름
    //   어느 쪽이든 판매자 저장은 REQUIRES_NEW라 바깥 트랜잭션 설정과 상관없이 동작이 같음
    // 커넥션: Step 트랜잭션 1개 + 판매자 저장 1개 = Job 하나당 최대 2개 (판매자를 한 명씩 차례로 처리하므로 판매자 수와 상관없음)
    //   정산 Job은 수동·자동 상관없이 한 번에 하나만 도는 전제라 기본 풀(10개, 배치·API 공유)에서 고갈되지 않음. 판매자마다 커밋해 느려지는 건 감수
    //   지금은 동시 실행을 막는 장치가 없음 → 수동·자동 실행이 겹치지 않게 막는 처리는 #52
    //
    // TODO(#52): 판매자 수가 크게 늘면 처리 방식 수정 필요. 판매자마다 커밋하므로 커밋 수가 판매자 수만큼 늘어남
    //   (INSERT 수는 예전과 같음: IDENTITY라 원래도 행마다 INSERT. 늘어나는 건 커밋할 때의 디스크 로그 확정 시간)
    //   예) 판매자 10만 명이면 커밋 1,000번 → 100,000번, 커밋 1번 1~5ms로 어림하면 약 2~8분 늘어남 (실측 아님)
    //   수정안 후보: 판매자 N명을 한 트랜잭션으로 묶어 저장해 보고, 실패한 묶음만 판매자 한 명씩 다시 저장 (평소 커밋 수는 예전 수준, 실패 때만 판매자 단위 격리)
    @Override
    @Transactional(readOnly = true)
    public PayoutDraftResult draft(Long snapshotId, int limit) {
        List<Long> sellerIds = payoutQueryRepository.findUndraftedSellerIds(snapshotId, limit);
        if (sellerIds.isEmpty()) {
            return new PayoutDraftResult(0, 0);
        }

        List<PayoutCandidateItem> candidates = payoutQueryRepository.findUndraftedCandidates(snapshotId, sellerIds);
        // 판매자는 뽑혔는데 후보가 0건 = 두 조회 조건 불일치(버그). 그대로 두면 같은 판매자가 계속 뽑혀 무한 반복되므로 실패시킴
        if (candidates.isEmpty()) {
            throw new RestApiException(PayoutErrorCode.UNSETTLED_CANDIDATES_NOT_FOUND);
        }

        // 판매자별로 묶음 (판매자 몫·수수료 모두 그 판매자 트랜잭션에서 함께 저장)
        Map<Long, List<PayoutCandidateItem>> candidatesBySeller = new LinkedHashMap<>();
        for (PayoutCandidateItem candidate : candidates) {
            Long sellerId = candidate.getSellerId();
            if (!candidatesBySeller.containsKey(sellerId)) {
                candidatesBySeller.put(sellerId, new ArrayList<>());
            }
            candidatesBySeller.get(sellerId).add(candidate);
        }

        int failedSellerCount = 0;
        for (Map.Entry<Long, List<PayoutCandidateItem>> entry : candidatesBySeller.entrySet()) {
            Long sellerId = entry.getKey();
            List<PayoutCandidateItem> sellerCandidates = entry.getValue();
            try {
                payoutSellerDraftService.saveDraft(snapshotId, sellerId, sellerCandidates);
            } catch (RuntimeException e) {
                // 예외를 다시 던지지 않고 기록으로 남기므로 팀 로그 규칙의 "던질 때 로그 금지"에 해당하지 않음
                // 그대로 묻히지 않게 스택 트레이스까지 WARN으로 남김 (계속 진행하지만 원인 확인이 필요한 이상)
                log.warn("정산 draft 판매자 처리 실패, 주문별로 다시 시도하고 계속 진행. snapshotId={}, sellerId={}, 후보 수={}",
                        snapshotId, sellerId, sellerCandidates.size(), e);
                // 실패 기록 저장마저 실패하면 예외가 그대로 나가 Job이 멈춤
                // 기록 없이 넘어가면 그 후보가 draft에도 실패에도 없어 verify가 "draft 누락"으로 그 주문들을 잠금 (#290)
                //   → 일시적 오류였어도 관리자가 풀어야 하므로, 여기서 멈추는 편이 원인이 분명하고 뒷수습도 적음
                // TODO(#52): 실패 기록 저장마저 실패할 때의 처리는 고민 중 (지금은 위 이유로 Job을 멈춤)
                //   다른 방법 후보: 실패 기록 저장 재시도, 기록 없이 그 판매자만 건너뛰고 verify 규칙에서 따로 허용 등 → 어떻게 할지 #52에서 정함
                // 판매자 전체가 아니라 오류를 일으킨 주문만 실패로 남기려고 주문별로 다시 시도함 (#534)
                if (!retryByOrder(snapshotId, sellerId, sellerCandidates, e)) {
                    failedSellerCount++;
                }
            }
        }
        return new PayoutDraftResult(sellerIds.size(), failedSellerCount);
    }

    // 판매자 저장이 실패한 뒤, 후보를 주문별로 나눠 다시 저장해 봄 (#534). 저장된 주문이 하나라도 있으면 true
    //   예) 판매자 A의 주문 100(오류 원인)과 주문 200(정상) → 200만 그날 정산, 100 후보만 UNEXPECTED_ERROR
    //   전에는 어느 주문 때문인지 몰라 판매자 후보 전부를 실패로 남겨, 정상 주문도 하루 늦어지고 #549 실패 횟수도 함께 쌓였음
    // 순서
    //   1. 주문마다 시험 저장(trySaveDraft: 써 보고 롤백). 실패한 주문은 그 주문의 예외로 바로 실패 기록
    //   2. 통과한 주문을 모아 판매자 draft 1건으로 진짜 저장 (판매자 draft는 스냅샷당 판매자 1건이라 주문마다 저장할 수 없음)
    //   3. 진짜 저장마저 실패하면(주문 하나씩은 되는데 합치면 안 되는 경우) 통과했던 주문도 그 예외로 실패 기록
    // 주문이 1개뿐이면 다시 해도 같은 저장이라 시험 없이 바로 실패 기록
    //   대가: 데드락 같은 일시적 오류는 다시 하면 될 수도 있는데, 주문이 여럿인 판매자만 사실상 한 번 더 시도받음 (주문 1개면 다음 실행에서 다시 시도)
    // 다시 시도 상한은 두지 않음(사용자 결정): 주문마다 시험 저장 1번
    //   대가: DB 연결이 끊기는 등 주문과 상관없는 오류면 모든 주문이 시험 저장에서 실패해, 주문 수만큼 시간이 더 걸림
    //   그 경우 실패 기록 저장도 실패할 가능성이 커서, 보통 첫 기록에서 예외가 나가 Job이 멈춤 (위 catch 주석과 같은 이유)
    // 판매자 안에서 주문으로 나누므로, 한 주문에 판매자가 여럿이면 그 판매자 몫만 나뉨 (다른 판매자 후보는 각자 판매자 저장에서 처리)
    private boolean retryByOrder(Long snapshotId, Long sellerId, List<PayoutCandidateItem> sellerCandidates,
                                 RuntimeException sellerFailure) {
        Map<Long, List<PayoutCandidateItem>> candidatesByOrder = new LinkedHashMap<>();
        for (PayoutCandidateItem candidate : sellerCandidates) {
            Long orderId = candidate.getOrderId();
            if (!candidatesByOrder.containsKey(orderId)) {
                candidatesByOrder.put(orderId, new ArrayList<>());
            }
            candidatesByOrder.get(orderId).add(candidate);
        }
        if (candidatesByOrder.size() == 1) {
            payoutSellerDraftService.recordUnexpectedError(snapshotId, sellerCandidates, sellerFailure);
            return false;
        }

        List<PayoutCandidateItem> passedCandidates = new ArrayList<>();
        int passedOrderCount = 0;
        for (Map.Entry<Long, List<PayoutCandidateItem>> entry : candidatesByOrder.entrySet()) {
            List<PayoutCandidateItem> orderCandidates = entry.getValue();
            try {
                payoutSellerDraftService.trySaveDraft(snapshotId, sellerId, orderCandidates);
                passedCandidates.addAll(orderCandidates);
                passedOrderCount++;
            } catch (RuntimeException orderFailure) {
                // 스택 트레이스는 판매자 실패 WARN에 이미 남김. 주문마다 또 남기면 DB 장애 때 주문 수만큼 쌓여 예외 종류·메시지만 남김
                log.warn("정산 draft 주문 처리 실패. snapshotId={}, sellerId={}, orderId={}, 예외={}: {}",
                        snapshotId, sellerId, entry.getKey(), orderFailure.getClass().getSimpleName(), orderFailure.getMessage());
                payoutSellerDraftService.recordUnexpectedError(snapshotId, orderCandidates, orderFailure);
            }
        }
        if (passedCandidates.isEmpty()) {
            return false;
        }

        try {
            payoutSellerDraftService.saveDraft(snapshotId, sellerId, passedCandidates);
        } catch (RuntimeException mergedFailure) {
            log.warn("정산 draft 주문별 다시 시도 후 합친 저장 실패, 통과한 주문도 실패로 기록. snapshotId={}, sellerId={}, 주문 수={}",
                    snapshotId, sellerId, passedOrderCount, mergedFailure);
            payoutSellerDraftService.recordUnexpectedError(snapshotId, passedCandidates, mergedFailure);
            return false;
        }
        log.warn("정산 draft 주문별 다시 시도로 일부 주문 저장. snapshotId={}, sellerId={}, 저장한 주문 수={}, 실패한 주문 수={}",
                snapshotId, sellerId, passedOrderCount, candidatesByOrder.size() - passedOrderCount);
        return true;
    }

    // 검증을 통과한 draft를 payout·payout_item으로 확정
    // 판매자 draft 1건 → 판매자 몫 payout 1건, 시스템 draft 항목 전체 → 시스템 몫 payout 1건 (항목이 없으면 만들지 않음)
    //
    // 결과 검증에 걸린 주문의 항목은 빼고 확정 (#290, 기준은 PayoutVerifyExclusion)
    //   판매자 몫·시스템 몫(수수료) 모두 같은 기준으로 거름 → 걸린 주문의 수수료도 시스템 몫에 들어가지 않음
    //   항목이 모두 빠진 판매자는 정산을 만들지 않음 (0원 정산이 장부에 남지 않게)
    //   빠진 항목은 실패 기록(VERIFY_MISMATCH)으로 주문이 잠겨, 관리자가 확인하기 전까지 다음 배치에서도 정산되지 않음
    //
    // 반드시 단일 트랜잭션 (분리 금지)
    // 시스템 몫(payee_id = NULL)은 payout UK(snapshot_id, payee_id)로 중복을 막지 못함 (MySQL은 NULL끼리 같은 값으로 보지 않음)
    //   예) 같은 스냅샷을 실수로 다시 commit → 시스템 몫 (12, NULL)이 또 저장되는 것은 UK가 못 막음
    // 대신 같은 트랜잭션 안에서 payout_item을 저장할 때 UK(candidate_id) 위반으로 실패 → 시스템 몫까지 전체 롤백되어 중복이 남지 않음
    // 트랜잭션을 나누면 시스템 몫이 먼저 커밋되어 중복이 조용히 남음
    @Override
    @Transactional
    public void commit(Long snapshotId) {
        PayoutVerifyExclusion exclusion = PayoutVerifyExclusion.from(payoutQueryRepository.findFailureRows(snapshotId));
        int excludedItemCount = 0;

        List<Payout> payouts = new ArrayList<>();
        for (PayoutSellerDraft draft : payoutSellerDraftRepository.findBySnapshotId(snapshotId)) {
            List<PayoutSellerDraftItem> items = new ArrayList<>();
            for (PayoutSellerDraftItem item : draft.getItems()) {
                if (exclusion.excludes(item.getOrderId(), item.getCandidateId())) {
                    excludedItemCount++;
                } else {
                    items.add(item);
                }
            }
            if (!items.isEmpty()) {
                payouts.add(Payout.forSeller(snapshotId, draft.getSellerId(), items));
            }
        }

        List<PayoutSystemDraftItem> systemItems = new ArrayList<>();
        for (PayoutSystemDraftItem item : payoutSystemDraftItemRepository.findBySnapshotId(snapshotId)) {
            if (exclusion.excludes(item.getOrderId(), item.getCandidateId())) {
                excludedItemCount++;
            } else {
                systemItems.add(item);
            }
        }
        if (!systemItems.isEmpty()) {
            payouts.add(Payout.forSystem(snapshotId, systemItems));
        }

        payoutRepository.saveAll(payouts);

        // 지갑 Spring 이벤트와 Kafka outbox 줄을 payout과 같은 트랜잭션에서 남겨, 확정과 함께 커밋·롤백되게 함
        List<PayoutOutbox> outboxes = new ArrayList<>();
        for (Payout payout : payouts) {
            // 시스템 몫은 정산 테이블에만 기록하고 지갑으로 보내지 않음 (지갑 담당자와 협의 완료)
            // 0원 이하 정산은 지갑 이벤트를 보내지 않음 (#106, 지갑 쪽 요청)
            //   0원: 지갑에 반영할 금액이 없음
            //   음수: 아직 음수(환불·차감) 처리를 하지 않아 지갑이 받을 수 없음 → 환불 작업 때 넘기는 방법을 다시 정함
            // 정산(payout) 자체는 장부 기록으로 그대로 남김
            if (payout.isSystem() || payout.getAmount() <= 0) {
                continue;
            }
            payoutModuleEventPublisher.publishSellerPayoutCreated(new SellerPayoutCreated(
                    payout.getId(),
                    payout.getPayeeId(),
                    payout.getAmount(),
                    payout.getCreatedAt()
            ));
            outboxes.add(PayoutOutbox.pending(payout.getId()));
        }
        payoutOutboxRepository.saveAll(outboxes);

        // 팀 로그 규칙(service.md "로그"): INFO는 업무상 의미 있는 사건에 남기고, 식별자는 key={} 형태로 넣음
        // 정산 확정은 돈이 장부에 기록되는 시점이라 INFO로 남김
        log.info("정산 확정. snapshotId={}, payout 건수={} (시스템 몫 {}건), 결과 검증 실패로 뺀 draft 항목 수={}",
                snapshotId, payouts.size(), systemItems.isEmpty() ? 0 : 1, excludedItemCount);
    }

    // outbox의 PENDING 줄을 덜 시도한 줄부터 한 줄씩 Kafka로 보냄. 이전 실행에서 못 보낸 줄도 함께 보냄 (#309)
    // 이 메서드는 트랜잭션을 더 만들지 않고, 보냄·실패 기록만 줄마다 짧은 별도 트랜잭션(PayoutOutboxService)에 남김
    // 그래서 한 줄의 실패가 앞 줄의 보냄 기록을 되돌리지 않음
    // 실패해도 멈추지 않음: 정산·지갑 입금은 이미 끝났고 남은 줄은 다음 실행이 보냄. 같은 정산이 두 번 가도 지갑은 payoutId로 막음
    // Kafka 발송이든 보냄·실패 기록(DB)이든 예외를 밖으로 내지 않음: 정산이 끝났는데 발송 단계 문제로 Job이 FAILED가 되지 않게
    // 단 연속으로 실패하면 브로커·DB 장애로 보고 이번 발송을 멈춤 (줄마다 최대 120초씩 기다리지 않게)
    @Override
    public PayoutPublishResult publishPending() {
        List<PayoutOutboxRow> rows = payoutOutboxRepository.findPendingRows();
        int sent = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        for (PayoutOutboxRow row : rows) {
            if (sendAndMarkSent(row)) {
                sent++;
                consecutiveFailures = 0;
                continue;
            }
            failed++;
            // 기다리다 스레드가 중단됐으면 다음 줄을 헛되이 실패로 기록하지 않고 멈춤 (남은 줄은 PENDING)
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_CONSECUTIVE_PUBLISH_FAILURES) {
                break;
            }
        }

        int remaining = rows.size() - sent - failed;
        log.info("정산 Kafka 발송. 보낸 수={}, 실패 수={}, 멈춰서 남은 수={}", sent, failed, remaining);
        return new PayoutPublishResult(sent, failed, remaining);
    }

    // 한 줄을 Kafka로 보내고 보냄으로 기록. 어디서 실패하든 줄은 PENDING으로 남아 다음 실행이 다시 보냄
    private boolean sendAndMarkSent(PayoutOutboxRow row) {
        try {
            payoutEventPublisher.publishSellerPayoutCreated(new SellerPayoutCreated(
                    row.payoutId(), row.payeeId(), row.amount(), row.payoutCreatedAt()));
        } catch (RuntimeException e) {
            log.warn("정산 Kafka 발송 실패, 다음 실행에서 다시 보냄. payoutId={}, outboxId={}", row.payoutId(), row.outboxId(), e);
            recordFailure(row, e);
            return false;
        }
        try {
            payoutOutboxService.markSent(row.outboxId());
            return true;
        } catch (RuntimeException e) {
            // Kafka는 이미 받았는데 DB에 못 적음. Kafka 실패가 아니라서 실패 기록은 남기지 않고, 다음 실행에서 한 번 더 감
            log.warn("정산 outbox 보냄 기록 실패, 다음 실행에서 Kafka로 한 번 더 보냄. payoutId={}, outboxId={}", row.payoutId(), row.outboxId(), e);
            return false;
        }
    }

    // 실패 기록마저 저장하지 못해도 줄은 PENDING이라 다음 실행이 다시 보냄. 시도 수·오류만 빠짐
    private void recordFailure(PayoutOutboxRow row, RuntimeException cause) {
        try {
            payoutOutboxService.recordFailure(row.outboxId(), failureMessage(cause));
        } catch (RuntimeException e) {
            log.warn("정산 outbox 실패 기록 저장 실패. payoutId={}, outboxId={}", row.payoutId(), row.outboxId(), e);
        }
    }

    // 발행기 예외에는 어느 정산인지만 있고 실제 원인은 cause에 있어, 원인 쪽을 남김
    private static String failureMessage(RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.toString();
    }

    // 결과 검증(verifyStep): 스냅샷과 draft를 후보 단위로 비교해, 어긋난 후보의 주문을 이번 정산에서 빼고 잠금 (#290)
    // 나머지 주문은 commitStep으로 넘어가 정산됨 (전에는 하나라도 어긋나면 그날 정산 전체가 멈춤)
    // 판정 규칙은 PayoutDraftValidator, 빼는 기준은 PayoutVerifyExclusion 참고
    //
    // 순서
    //   1) 코드 고장 검사 → 걸리면 아무것도 기록하지 않고 전용 에러 코드로 Job 전체 중단
    //      판매자 합계 불일치, 판매자 항목의 스냅샷 번호 불일치, 실패 후보가 draft에 섞임: 모든 판매자가 같이 쓰는 코드가 망가진 것이라 일부만 빼도 나머지를 믿기 어려움
    //      기록·잠금을 먼저 남기지 않는 이유: 망가진 코드로 판정한 결과라 대량으로 잘못 잠글 수 있고, 고친 뒤 재실행하면 깨끗하게 다시 처리됨
    //   2) 후보 단위 검사 → 걸린 주문의 실패 기록(VERIFY_MISMATCH)을 만들고, 빼고 나면 정산할 항목이 몇 건 남는지 셈
    //   3) 걸린 주문을 빼니 0건 = 전부 걸림 → 1)과 같이 기록 없이 Job 전체 중단 (아래 본문 주석)
    //   4) 일부만 걸림 → 걸린 주문의 이번 스냅샷 후보(모든 판매자)를 기록하고 주문 잠금, 나머지는 commitStep에서 정산
    //      이미 이번 스냅샷에 실패 기록이 있는 후보는 건너뜀 (실패 기록 UK, 그 후보는 원래 draft에도 없음)
    //
    // 결과는 세 가지: 통과 / 일부 걸림(기록·잠금 후 계속, Job COMPLETED) / 중대 문제(기록 없이 예외, Job FAILED)
    // 실패 기록은 verifyStep 트랜잭션 안에서 저장해 Step이 끝날 때 함께 커밋됨
    //   저장한 뒤 이 Step이 실패로 끝나는 경로가 없어서 따로 커밋할 필요가 없음 (전부 걸린 경우는 저장 전에 멈춤)
    // 1)·2)의 조회가 같은 시점의 데이터를 봄: MySQL 기본 격리 수준(REPEATABLE READ)에서 한 트랜잭션 안의 조회는 같은 스냅샷을 읽음
    @Override
    @Transactional
    public PayoutVerifyResult verify(Long snapshotId) {
        // 1) 코드 고장 검사
        // 팀 규칙(service.md "로그"): 예외를 던질 때 로그를 따로 남기지 않음 → 원인을 예외 메시지에 담아 배치 실패 기록(EXIT_MESSAGE)·로그에 한 번만 남김
        List<String> totalMismatches = PayoutDraftValidator.checkSellerTotals(payoutQueryRepository.findSellerTotalRows(snapshotId));
        if (!totalMismatches.isEmpty()) {
            // TODO(#52): 코드 고장 즉시 알림 필요(슬랙 등). 지금은 전용 에러 코드로 Job만 멈추고 알림이 없어 배치 결과를 봐야 알 수 있음
            //   어디서 보나: BATCH_STEP_EXECUTION에서 payoutVerifyStep이 FAILED인 실행의 EXIT_MESSAGE (판매자 ID와 헤더·항목 금액이 들어 있음)
            //   멈췄을 때 할 일: 판매자 합계 계산(PayoutSellerDraft.create) 최근 변경 확인·수정 → 재실행
            //   잠금·실패 기록을 남기지 않으므로 정리할 데이터는 없음 (다음 정기 배치나 /payouts/run으로 새 스냅샷부터 다시 처리)
            throw new RestApiException(PayoutErrorCode.DRAFT_SELLER_TOTAL_MISMATCH, String.format(
                    "판매자 임시 정산 합계 불일치(코드 오류). snapshotId=%d, 불일치 판매자 수=%d, 내용(최대 %d건)=%s",
                    snapshotId, totalMismatches.size(), MESSAGE_ITEM_LIMIT, limit(totalMismatches)));
        }

        List<Long> mismatchedSnapshotItems = payoutQueryRepository.findSellerDraftItemsWithMismatchedSnapshot(snapshotId);
        if (!mismatchedSnapshotItems.isEmpty()) {
            // TODO(#52): 코드 고장 즉시 알림 필요. 지금은 전용 에러 코드로 Job만 멈추고 알림이 없어 배치 결과를 봐야 알 수 있음
            //   어디서 보나: BATCH_STEP_EXECUTION에서 payoutVerifyStep이 FAILED인 실행의 EXIT_MESSAGE (후보 ID가 들어 있음)
            //   멈췄을 때 할 일: 판매자 draft 항목 생성(PayoutSellerDraftItem 생성자의 스냅샷 번호 복사) 최근 변경 확인·수정 → 재실행
            //   잠금·실패 기록을 남기지 않으므로 정리할 데이터는 없음 (다음 정기 배치나 /payouts/run으로 새 스냅샷부터 다시 처리)
            throw new RestApiException(PayoutErrorCode.DRAFT_ITEM_SNAPSHOT_MISMATCH, String.format(
                    "판매자 임시 정산 항목의 스냅샷 불일치(코드 오류). snapshotId=%d, 항목 수=%d, 후보 ID(최대 %d건)=%s",
                    snapshotId, mismatchedSnapshotItems.size(), MESSAGE_ITEM_LIMIT, limit(mismatchedSnapshotItems)));
        }

        List<SnapshotItemRow> snapshotItems = payoutQueryRepository.findSnapshotItemRows(snapshotId);
        List<DraftItemRow> draftItems = new ArrayList<>(payoutQueryRepository.findSellerDraftItemRows(snapshotId));
        draftItems.addAll(payoutQueryRepository.findSystemDraftItemRows(snapshotId));
        List<FailureRow> failures = payoutQueryRepository.findFailureRows(snapshotId);

        PayoutDraftValidator.Result result = PayoutDraftValidator.validate(
                new PayoutDraftValidator.Input(snapshotItems, draftItems, failures));
        if (!result.failedCandidatesInDraft().isEmpty()) {
            // TODO(#52): 코드 고장 즉시 알림 필요. 지금은 전용 에러 코드로 Job만 멈추고 알림이 없어 배치 결과를 봐야 알 수 있음
            //   어디서 보나: BATCH_STEP_EXECUTION에서 payoutVerifyStep이 FAILED인 실행의 EXIT_MESSAGE (후보 ID가 들어 있음)
            //   멈췄을 때 할 일: draft 대상 조회 조건(PayoutQueryRepository.undraftedInSnapshot ④) 확인·수정 → 재실행
            //   잠금·실패 기록을 남기지 않으므로 정리할 데이터는 없음 (다음 정기 배치나 /payouts/run으로 새 스냅샷부터 다시 처리)
            throw new RestApiException(PayoutErrorCode.DRAFT_INCLUDES_FAILED_CANDIDATE, String.format(
                    "실패 후보가 임시 정산에 들어감(코드 오류). snapshotId=%d, 후보 수=%d, 후보 ID(최대 %d건)=%s",
                    snapshotId, result.failedCandidatesInDraft().size(), MESSAGE_ITEM_LIMIT,
                    limit(result.failedCandidatesInDraft())));
        }

        // 2) 걸린 주문의 실패 기록을 만들고, 빼고 나면 정산할 항목이 몇 건 남는지 셈 (commitStep과 같은 기준)
        List<PayoutFailure> verifyFailures = toVerifyFailures(snapshotId, result.orderMismatches(), snapshotItems, failures);
        PayoutVerifyExclusion exclusion = PayoutVerifyExclusion.from(failures);
        exclusion.addAll(verifyFailures);
        int remainingItemCount = 0;
        for (DraftItemRow draftItem : draftItems) {
            if (!exclusion.excludes(draftItem.orderId(), draftItem.candidateId())) {
                remainingItemCount++;
            }
        }

        // 3) 걸린 주문을 빼고 나니 정산할 항목이 0건이면, 실패 기록·잠금을 남기지 않고 전체를 멈춤
        //   전부 걸렸다 = 대부분 모든 주문이 같이 쓰는 코드가 망가진 것 (위 코드 고장 세 가지와 같은 성격)
        //   이때 기록하면 그날 대상 주문이 전부 잠김 → 코드를 고쳐도 해제 기능이 없어(#291) DB에서 직접 지워야 하고,
        //     잠긴 지 약 3일이 지나면 지워도 조회 기간 밖이라 다시 정산되지 않음
        //   기록 없이 멈추면 코드를 고친 뒤 재실행만으로 새 스냅샷에서 깨끗하게 다시 처리됨
        //     (정산이 만들어지지 않는 동안은 조회 하한(정산을 만든 마지막 정기 실행의 요청일 기준)도 그대로라 밀린 후보가 계속 조회됨)
        //   한계: 그날 대상이 정말로 틀린 주문뿐이었어도 잠기지 않음 → 다음 배치에서 다시 걸려 또 멈추므로 매일 FAILED로 드러남
        // "걸려서 0건"일 때만 해당: 대상이 없는 날, validate·draft 실패만으로 0건인 날은 걸린 주문이 없어 정상 진행(COMPLETED)
        if (remainingItemCount == 0 && exclusion.orderCount() > 0) {
            // TODO(#52): 코드 고장 즉시 알림 필요. 지금은 전용 에러 코드로 Job만 멈추고 알림이 없어 배치 결과를 봐야 알 수 있음
            //   어디서 보나: BATCH_STEP_EXECUTION에서 payoutVerifyStep이 FAILED인 실행의 EXIT_MESSAGE (걸린 주문 ID와 원인이 들어 있음)
            //   멈췄을 때 할 일: 메시지의 원인(어느 검사에 걸렸는지)으로 draft 관련 최근 변경 확인·수정 → 재실행
            //   잠금·실패 기록을 남기지 않으므로 정리할 데이터는 없음 (다음 정기 배치나 /payouts/run으로 새 스냅샷부터 다시 처리)
            throw new RestApiException(PayoutErrorCode.DRAFT_ALL_ORDERS_MISMATCHED, String.format(
                    "결과 검증에 모든 주문이 걸림(코드 오류 의심). snapshotId=%d, 걸린 주문 수=%d, 걸린 후보 수=%d, 원인(최대 %d건)=%s",
                    snapshotId, exclusion.orderCount(), verifyFailures.size(), MESSAGE_ITEM_LIMIT,
                    limit(result.orderMismatches())));
        }

        // 4) 일부 주문만 걸림 → 그 주문의 후보를 실패로 기록 (주문 잠금), 나머지는 commitStep에서 정산
        //   verifyStep 트랜잭션 안에서 저장해 Step이 끝날 때 함께 커밋됨
        if (!verifyFailures.isEmpty()) {
            payoutFailureRepository.saveAll(verifyFailures);
        }
        logExcludedOrders(snapshotId, verifyFailures);

        // 팀 로그 규칙(service.md "로그"): 업무상 의미 있는 사건이라 INFO
        log.info("정산 결과 검증. snapshotId={}, 스냅샷 후보 수={}, 정산할 draft 항목 수={}, 결과 검증 실패로 뺀 주문 수={}, 새로 기록한 실패 수={}",
                snapshotId, snapshotItems.size(), remainingItemCount, exclusion.orderCount(), verifyFailures.size());
        return new PayoutVerifyResult(remainingItemCount, exclusion.orderCount(), verifyFailures.size());
    }

    // 걸린 주문마다 이번 스냅샷 후보 전체(모든 판매자)의 VERIFY_MISMATCH 실패 기록을 만듦
    //   대상: 원인 후보 + 그 주문의 스냅샷 후보
    //   draft 항목 쪽을 따로 보지 않는 이유: 스냅샷에 없는 항목이나 주문이 다르게 들어간 항목은 이미 원인 후보로 들어가 있고,
    //   주문이 같은 항목은 스냅샷 후보와 같음
    //   상세: 원인 후보는 "[원인] 어긋난 내용", 나머지는 "[함께 보류] … (원인 후보 ID)"
    //   이미 이번 스냅샷에 실패 기록이 있는 후보는 뺌 (실패 기록 UK. validate에서 빠진 후보라 draft에도 없음)
    // 같은 후보가 두 주문에 걸리면(주문 ID가 어긋난 경우) 처음 정한 상세로 1건만 만듦
    private List<PayoutFailure> toVerifyFailures(Long snapshotId, List<PayoutDraftValidator.OrderMismatch> mismatches,
                                                 List<SnapshotItemRow> snapshotItems, List<FailureRow> failures) {
        if (mismatches.isEmpty()) {
            return List.of();
        }
        Map<Long, String> details = new LinkedHashMap<>();
        for (PayoutDraftValidator.OrderMismatch mismatch : mismatches) {
            for (Map.Entry<Long, String> cause : mismatch.causes().entrySet()) {
                if (!details.containsKey(cause.getKey())) {
                    details.put(cause.getKey(), PayoutDraftValidator.CAUSE_PREFIX + cause.getValue());
                }
            }
        }
        // 주문별로 한 번 묶어 둠: 걸린 주문마다 스냅샷 전체를 다시 훑으면 (걸린 주문 수 × 스냅샷 건수)만큼 비교하게 됨
        //   걸린 주문이 많은 날이 곧 코드 버그로 대부분 걸린 날이라, 그날 verify가 크게 느려지지 않게 함
        Map<Long, List<SnapshotItemRow>> snapshotItemsByOrder = new LinkedHashMap<>();
        for (SnapshotItemRow snapshotItem : snapshotItems) {
            if (!snapshotItemsByOrder.containsKey(snapshotItem.orderId())) {
                snapshotItemsByOrder.put(snapshotItem.orderId(), new ArrayList<>());
            }
            snapshotItemsByOrder.get(snapshotItem.orderId()).add(snapshotItem);
        }
        for (PayoutDraftValidator.OrderMismatch mismatch : mismatches) {
            String heldDetail = String.format(PayoutDraftValidator.HELD_DETAIL, mismatch.causes().keySet());
            for (SnapshotItemRow snapshotItem : snapshotItemsByOrder.getOrDefault(mismatch.orderId(), List.of())) {
                if (!details.containsKey(snapshotItem.candidateId())) {
                    details.put(snapshotItem.candidateId(), heldDetail);
                }
            }
        }
        for (FailureRow failure : failures) {
            details.remove(failure.candidateId());
        }
        if (details.isEmpty()) {
            return List.of();
        }

        List<PayoutFailure> verifyFailures = new ArrayList<>();
        // TODO(#52): 걸린 후보가 아주 많으면(수만 건) IN 목록이 길어짐. 그런 날(대부분 걸림)이 실제로 생기면 나눠 조회하는 것을 검토
        for (PayoutCandidateItem candidate : payoutCandidateItemRepository.findAllByIdInOrderByIdAsc(details.keySet())) {
            // 원인 내용이 길거나 원인 후보가 많으면 255자를 넘을 수 있어 잘라서 기록함
            String detail = PayoutFailure.fitDetail(details.get(candidate.getId()));
            verifyFailures.add(PayoutFailure.of(snapshotId, candidate, PayoutFailureReason.VERIFY_MISMATCH, detail));
        }
        return verifyFailures;
    }

    // 결과 검증으로 주문이 새로 빠질 때마다 WARN: 코드 버그 신호이고, 주문이 잠겨 사람이 원인을 확인해야 함
    // 일부 주문만 빠지면 Job은 COMPLETED라 이 로그(와 실패 기록, WRITE_SKIP_COUNT)로 알아차려야 함 (즉시 알림은 #52)
    // 판매자 수를 남기는 이유: 같은 주문의 다른 판매자 후보도 함께 빠져, 영향받은 판매자를 바로 알 수 있게
    private void logExcludedOrders(Long snapshotId, List<PayoutFailure> verifyFailures) {
        Map<Long, List<PayoutFailure>> failuresByOrder = new LinkedHashMap<>();
        for (PayoutFailure failure : verifyFailures) {
            if (!failuresByOrder.containsKey(failure.getOrderId())) {
                failuresByOrder.put(failure.getOrderId(), new ArrayList<>());
            }
            failuresByOrder.get(failure.getOrderId()).add(failure);
        }

        for (Map.Entry<Long, List<PayoutFailure>> entry : failuresByOrder.entrySet()) {
            Set<Long> sellerIds = new HashSet<>();
            String cause = null;
            for (PayoutFailure failure : entry.getValue()) {
                sellerIds.add(failure.getSellerId());
                if (cause == null && failure.getDetail().startsWith(PayoutDraftValidator.CAUSE_PREFIX)) {
                    cause = failure.getDetail();
                }
            }
            // 원인 후보의 기록이 다른 주문으로 남은 경우(draft 항목의 주문 ID가 어긋남): 함께 보류 상세에 원인 후보 ID가 있어 그걸 남김
            if (cause == null) {
                cause = entry.getValue().get(0).getDetail();
            }
            log.warn("정산 결과 검증 불일치로 주문 제외·잠금(코드 버그 확인 필요). snapshotId={}, orderId={}, 원인={}, 판매자 수={}, 후보 수={}",
                    snapshotId, entry.getKey(), cause, sellerIds.size(), entry.getValue().size());
        }
    }

    // 예외 메시지용으로 앞 MESSAGE_ITEM_LIMIT개만 남김
    private static <T> List<T> limit(List<T> values) {
        return values.subList(0, Math.min(values.size(), MESSAGE_ITEM_LIMIT));
    }

    /**
     * 정산 1건 수동 실행을 요청한다. 지금은 막아 둔 기능이라 항상 예외를 던진다(성공하는 경우 없음).
     * 관리자 전용 기능이며 관리자 확인은 컨트롤러({@code @PreAuthorize})에서 한다. 기능을 열 때는 TODO 아래의 예외만 실제 실행 로직으로 바꾼다.
     *
     * @throws RestApiException 아직 열리지 않은 기능({@code FEATURE_NOT_SUPPORTED})
     */
    @Override
    public void runOrderItem(Long orderItemId) {
        // TODO(논의 필요): 1건 수동 실행은 재처리 정책(이미 정산된 건 처리, 실패 건 재시도 범위)이 정해지지 않아 막아 둠
        throw new RestApiException(PayoutErrorCode.FEATURE_NOT_SUPPORTED,
                "정산 1건 수동 실행은 아직 지원하지 않습니다. (orderItemId: " + orderItemId + ")");
    }

    /**
     * 정산 수동 조정을 요청한다. 지금은 막아 둔 기능이라 항상 예외를 던진다(성공하는 경우 없음).
     * 관리자 전용 기능이며 관리자 확인은 컨트롤러({@code @PreAuthorize})에서 한다. 기능을 열 때는 TODO 아래의 예외만 실제 조정 로직으로 바꾼다.
     *
     * @param adminId 요청한 관리자 ID. 조정 기능을 열면 정산후보의 adminId로 기록함 (#65 리뷰 후속: 요청 바디가 아닌 인증 정보에서 받음)
     * @throws RestApiException 아직 열리지 않은 기능({@code FEATURE_NOT_SUPPORTED})
     */
    @Override
    public void createAdjustment(Long adminId, PayoutAdjustmentCreateCommand command) {
        // TODO(논의 필요): 조정 번호(adjustmentId) 발급 방식(조정 요청 테이블에서 서버 발급 vs 클라이언트 전달)이 정해지지 않아 막아 둠
        throw new RestApiException(PayoutErrorCode.FEATURE_NOT_SUPPORTED,
                "정산 수동 조정은 아직 지원하지 않습니다. (orderItemId: " + command.orderItemId() + ")");
    }

    /**
     * 관리자가 정산 목록을 조회한다. 보낸 조건을 그대로 쓴다({@code payeeId}를 생략하면 전체).
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutSummaryResult> searchForAdmin(PayoutSearchQuery query) {
        return searchPayouts(query.payeeId(), query.system(), query.from(), query.to(), query.page(), query.size());
    }

    /**
     * 판매자가 본인 정산 목록을 조회한다. 수취인은 본인, 대상은 판매자 몫(시스템 몫 제외)으로 고정한다.
     *
     * @param sellerId 요청한 판매자의 회원 ID (토큰에서 받은 값)
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutSummaryResult> searchForSeller(Long sellerId, PayoutSellerSearchQuery query) {
        // 판매자 목록은 요청에 대상 ID가 없음: 토큰의 회원 ID로만 조회해서 URL·파라미터를 바꿔도 남의 정산을 요청할 수 없음
        // TODO(논의 필요): 지금은 회원 ID(sellerId)와 정산 수취인 ID(payeeId)가 같은 회원 번호라는 전제로 조회함
        // 관리자·판매자 번호 체계가 따로 정해지면 이 조회 조건도 함께 수정해야 함. 같은 전제가 checkOwnPayout()에도 있음 → 함께 수정
        return searchPayouts(sellerId, false, query.from(), query.to(), query.page(), query.size());
    }

    // 관리자·판매자 목록 조회의 공통 부분. 둘은 조건만 다르고 조회 방식은 같음
    private Page<PayoutSummaryResult> searchPayouts(Long payeeId, Boolean system, LocalDateTime from, LocalDateTime to,
                                                    int page, int size) {
        return payoutQueryRepository.searchPayouts(payeeId, system, from, to, PageRequest.of(page, size))
                .map(PayoutSummaryResult::from);
    }

    /**
     * 관리자가 정산 1건의 상세(정산 대상 기간, 항목 수 포함)를 조회한다.
     *
     * @throws RestApiException 정산이 없으면 {@code PAYOUT_NOT_FOUND}
     * @throws IllegalStateException 정산의 스냅샷이 없으면 (외래키라 정상이면 생길 수 없는 데이터 손상)
     */
    @Override
    @Transactional(readOnly = true)
    public PayoutDetailResult getDetailForAdmin(Long payoutId) {
        return loadDetail(findPayout(payoutId));
    }

    /**
     * 판매자가 본인 정산 1건의 상세를 조회한다.
     * 결과는 관리자와 같고, 판매자에게 보여 주지 않을 값(시스템 몫 정산 ID)은 판매자 응답 DTO에서 뺀다.
     *
     * @param sellerId 요청한 판매자의 회원 ID (토큰에서 받은 값). 이 정산의 수취인이 본인인지 확인하는 데 씀
     * @throws RestApiException 정산이 없으면 {@code PAYOUT_NOT_FOUND}, 본인 정산이 아니거나 시스템 몫이면 {@code PAYOUT_ACCESS_DENIED}
     * @throws IllegalStateException 정산의 스냅샷이 없으면 (외래키라 정상이면 생길 수 없는 데이터 손상)
     */
    @Override
    @Transactional(readOnly = true)
    public PayoutDetailResult getDetailForSeller(Long sellerId, Long payoutId) {
        Payout payout = findPayout(payoutId);
        checkOwnPayout(sellerId, payout);
        return loadDetail(payout);
    }

    // 관리자·판매자 상세 조회의 공통 부분
    private PayoutDetailResult loadDetail(Payout payout) {
        // 정산의 snapshot_id는 외래키(fk_payout_snapshot_id)라 정상이면 스냅샷이 없을 수 없음
        // 데이터 손상은 사용자가 고칠 수 없는 시스템 오류라 표준 예외로 던져 500·ERROR 로그로 드러나게 함 (exception.md: 값의 출처 기준)
        PayoutSnapshot snapshot = payoutSnapshotRepository.findById(payout.getSnapshotId())
                .orElseThrow(() -> new IllegalStateException("정산의 스냅샷이 없습니다. payoutId=" + payout.getId()));
        long itemCount = payoutQueryRepository.countItemsByPayoutId(payout.getId());

        // 판매자 수수료는 판매자 정산이 아니라 같은 회차의 시스템 몫 정산에 항목으로 들어 있음
        // payout.snapshot_id는 같은 실행 회차에 만들어진 정산끼리 같은 값이라, 이 값으로 짝이 되는 시스템 몫 정산을 찾고 (payout 테이블)
        // 그 항목 중 이 판매자(seller_id) 몫만 더함 (payout_item 테이블). seller_id만으로 더하면 모든 회차의 수수료가 합쳐짐
        Long systemPayoutId = null;
        Long sellerFeeAmount = null;
        Long sellerFeeItemCount = null;
        if (!payout.isSystem()) {
            Optional<Payout> systemPayout = payoutRepository.findBySnapshotIdAndSystemTrue(payout.getSnapshotId());
            if (systemPayout.isPresent()) {
                CountAndAmount fee = payoutQueryRepository.summarizeSellerFeeItems(systemPayout.get().getId(), payout.getPayeeId());
                systemPayoutId = systemPayout.get().getId();
                sellerFeeAmount = fee.amount();
                sellerFeeItemCount = fee.count();
            }
        }

        return new PayoutDetailResult(payout.getId(), payout.getSnapshotId(), payout.getPayeeId(), payout.isSystem(),
                payout.getAmount(), payout.getCreatedAt(), snapshot.getPeriodFrom(), snapshot.getPeriodTo(), itemCount,
                systemPayoutId, sellerFeeAmount, sellerFeeItemCount);
    }

    /**
     * 관리자가 정산 1건에 묶인 정산 항목(payout_item)을 정산후보(payout_candidate_items)와 조인해 페이지 단위로 조회한다.
     * 후보에서는 생성 시각과 조정 정보(조정 번호, 관리자 ID, 사유)를 함께 가져온다.
     *
     * @throws RestApiException 정산이 없으면 {@code PAYOUT_NOT_FOUND}
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutItemResult> getItemsForAdmin(Long payoutId, PayoutItemSearchQuery query) {
        // 정산이 없으면 빈 목록이 아니라 404로 알려 주려고 먼저 조회
        findPayout(payoutId);
        return loadItems(payoutId, query);
    }

    /**
     * 판매자가 본인 정산 1건에 묶인 정산 항목(payout_item)을 정산후보(payout_candidate_items)와 조인해 페이지 단위로 조회한다.
     * 결과는 관리자와 같고, 판매자에게 보여 주지 않을 값(조정 번호, 조정한 관리자 ID)은 판매자 응답 DTO에서 뺀다.
     *
     * @param sellerId 요청한 판매자의 회원 ID (토큰에서 받은 값). 이 정산의 수취인이 본인인지 확인하는 데 씀
     * @throws RestApiException 정산이 없으면 {@code PAYOUT_NOT_FOUND}, 본인 정산이 아니거나 시스템 몫이면 {@code PAYOUT_ACCESS_DENIED}
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutItemResult> getItemsForSeller(Long sellerId, Long payoutId, PayoutItemSearchQuery query) {
        Payout payout = findPayout(payoutId);
        checkOwnPayout(sellerId, payout);
        return loadItems(payoutId, query);
    }

    // 관리자·판매자 항목 조회의 공통 부분
    private Page<PayoutItemResult> loadItems(Long payoutId, PayoutItemSearchQuery query) {
        return payoutQueryRepository.findItemsWithCandidate(payoutId, PageRequest.of(query.page(), query.size()))
                .map(PayoutQueryResultMapper::toItemResult);
    }

    /**
     * 관리자가 정산후보 목록을 조회한다. {@code sellerId}를 생략하면 전체 판매자, {@code unsettledOnly}면 아직 정산 전인 후보만 조회한다.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutCandidateResult> searchCandidatesForAdmin(PayoutCandidateSearchQuery query) {
        return searchCandidates(query.sellerId(), query.from(), query.to(), query.unsettledOnly(), false,
                query.page(), query.size());
    }

    /**
     * 판매자가 본인 정산후보 목록을 조회한다. 판매자는 본인으로 고정하고, 수수료(시스템 몫) 후보는 빼고 조회한다.
     * 수수료는 판매자 정산이 아니라 시스템 몫 정산에 들어가서, 정산 항목 조회처럼 판매자에게 줄 단위로 보여 주지 않는다
     * (판매자가 낸 수수료 합계는 정산 상세의 sellerFeeAmount로 본다). 조정 번호·관리자 ID는 판매자 응답 DTO에서 뺀다.
     *
     * @param sellerId 요청한 판매자의 회원 ID (토큰에서 받은 값)
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutCandidateResult> searchCandidatesForSeller(Long sellerId, PayoutSellerCandidateSearchQuery query) {
        // 판매자 목록은 요청에 대상 ID가 없음: 토큰의 회원 ID로만 조회해서 URL·파라미터를 바꿔도 남의 후보를 요청할 수 없음
        // 후보의 seller_id는 판매한 판매자 회원 ID라서, 정산 목록(payeeId)과 달리 번호 체계 전제(TODO)가 필요 없음
        return searchCandidates(sellerId, query.from(), query.to(), query.unsettledOnly(), true,
                query.page(), query.size());
    }

    // 관리자·판매자 정산후보 조회의 공통 부분. 둘은 조건만 다르고 조회 방식은 같음
    private Page<PayoutCandidateResult> searchCandidates(Long sellerId, LocalDateTime from, LocalDateTime to,
                                                         boolean unsettledOnly, boolean sellerShareOnly, int page, int size) {
        return payoutQueryRepository.searchCandidates(sellerId, from, to, unsettledOnly, sellerShareOnly, PageRequest.of(page, size))
                .map(PayoutQueryResultMapper::toCandidateResult);
    }

    private Payout findPayout(Long payoutId) {
        return payoutRepository.findById(payoutId)
                .orElseThrow(() -> new RestApiException(PayoutErrorCode.PAYOUT_NOT_FOUND));
    }

    // 판매자 상세·항목 조회에서 이 정산의 수취인이 본인인지 확인
    // 역할 확인은 컨트롤러(@PreAuthorize)로 옮겼지만 이 확인은 서비스에 남김: 역할만으로는 "어느 정산을 여는가"를 막을 수 없음
    //   정산은 회원 ID가 아니라 URL의 정산 ID로 가져오고, 그 번호는 요청자가 아무 값이나 넣을 수 있음
    //   예) 판매자 7이 /payouts/me/13(판매자 9의 정산)이나 /payouts/me/14(모든 판매자 수수료가 섞인 시스템 몫)를 요청 → 403
    // 시스템 몫은 payeeId가 null이라 이 비교에서 함께 걸러짐
    private void checkOwnPayout(Long sellerId, Payout payout) {
        // TODO(논의 필요): 지금은 회원 ID(sellerId)와 정산 수취인 ID(payeeId)가 같은 회원 번호라는 전제로 비교함
        // 관리자·판매자 번호 체계(번호 발급 방식)가 따로 정해지면 이 비교 방식도 함께 수정해야 함
        // 같은 전제가 searchForSeller()(목록 조회)에도 있음 → 함께 수정
        if (!sellerId.equals(payout.getPayeeId())) {
            throw new RestApiException(PayoutErrorCode.PAYOUT_ACCESS_DENIED);
        }
    }
}
