package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSellerDraftItem;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSnapshotItem;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.repository.PayoutFailureRepository;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutQueryRepository.CountAndAmount;
import com.ok.payout.repository.PayoutQueryRepository.DraftItemRow;
import com.ok.payout.repository.PayoutQueryRepository.FailureRow;
import com.ok.payout.repository.PayoutQueryRepository.SnapshotItemRow;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSnapshotItemRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import com.ok.payout.service.dto.PayoutAdjustmentCreateCommand;
import com.ok.payout.service.dto.PayoutCandidateResult;
import com.ok.payout.service.dto.PayoutCandidateSearchQuery;
import com.ok.payout.service.dto.PayoutDetailResult;
import com.ok.payout.service.dto.PayoutDraftResult;
import com.ok.payout.service.dto.PayoutItemResult;
import com.ok.payout.service.dto.PayoutItemSearchQuery;
import com.ok.payout.service.dto.PayoutSearchQuery;
import com.ok.payout.service.dto.PayoutSellerCandidateSearchQuery;
import com.ok.payout.service.dto.PayoutSellerSearchQuery;
import com.ok.payout.service.dto.PayoutSummaryResult;
import com.ok.payout.service.dto.PayoutVerifyResult;
import com.ok.payout.service.dto.SellerPayoutCreatedCommand;
import com.ok.payout.service.publisher.PayoutEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    // 코드 고장으로 전체를 멈출 때 예외 메시지에 담는 항목(판매자 합계 불일치 내용, 후보 ID) 최대 개수 (메시지가 너무 길어지지 않게, 나머지는 건수로만 남김)
    private static final int MESSAGE_ITEM_LIMIT = 20;

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
    // verifyStep의 결과 검증 실패 기록 저장 (별도 트랜잭션, #290)
    private final PayoutVerifyFailureService payoutVerifyFailureService;
    // 지갑으로 정산 확정을 알리는 발행 인터페이스. 발행 방식(Spring 이벤트 등)은 구현체(event 패키지)가 맡는다
    private final PayoutEventPublisher payoutEventPublisher;

    // 조회 상한: 실행일 − 대기 일수 00:00 (생성 후 대기 일수가 지난 후보만 정산)
    public LocalDateTime settlementTo(LocalDate runDate) {
        return runDate.minusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS).atStartOfDay();
    }

    // 조회 하한: 마지막 정산일 − (대기 일수 + 여유 일수) 00:00, 정산이 없으면 null(하한 없음)
    // 마지막 정산일 기준이라 배치가 밀리면 하한도 그만큼 과거로 내려가 밀린 후보까지 조회됨
    public LocalDateTime settlementFrom() {
        LocalDateTime last = payoutRepository.findFirstByOrderByIdDesc()
                .map(Payout::getCreatedAt)
                .orElse(null);
        if (last == null) {
            return null;
        }
        return last.toLocalDate()
                .minusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS + PayoutPolicy.SETTLEMENT_LOOKBACK_MARGIN_DAYS)
                .atStartOfDay();
    }

    // 이번 배치의 정산 대상을 스냅샷으로 고정하고 스냅샷 ID를 반환
    // 헤더와 목록을 한 트랜잭션에서 만들어, 중간에 실패하면 둘 다 남지 않음 (반쪽짜리 스냅샷 방지)
    // 이후 Step은 이 목록만 보고 처리하므로, 배치 도중 새로 생긴 정산후보는 다음 배치로 넘어감
    // 금액 검사로 잠긴 주문(#195)의 후보는 스냅샷에 넣지 않음 → 실패 기록도 다시 생기지 않고 정산 대상에서 계속 빠짐
    //
    // 지금은 팀 규칙(QueryDSL 조회 + JPA 저장)을 따라 대상을 Java로 불러와 저장함
    // 대상 전체를 메모리에 올리고, ID가 IDENTITY라 batch insert가 안 돼 건수만큼 INSERT가 나감
    // → 하루 정산후보가 수만 건을 넘어 메모리·시간이 부담되면 HQL INSERT ... SELECT(DB 안에서 1문장 복사)로 바꾸는 것을 고려
    @Transactional
    public Long createSnapshot(Long jobExecutionId, LocalDateTime from, LocalDateTime to) {
        PayoutSnapshot snapshot = payoutSnapshotRepository.save(PayoutSnapshot.create(jobExecutionId, from, to));

        List<PayoutCandidateItem> candidates = payoutQueryRepository.findUnsettledCandidatesInPeriod(from, to);
        List<PayoutSnapshotItem> items = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidates) {
            items.add(PayoutSnapshotItem.create(snapshot.getId(), candidate));
        }
        payoutSnapshotItemRepository.saveAll(items);

        // 팀 로그 규칙(service.md "로그"): INFO는 업무상 의미 있는 사건(예: 배치 종료)에 남기고, 식별자는 key={} 형태로 넣음
        // 스냅샷 생성은 배치가 맡은 대상이 정해지는 시점이라 INFO로 남김
        // jobExecutionId로 배치 실행 기록과, snapshotId로 이후 Step 로그(결과 검증으로 뺀 주문 등)와 이어서 볼 수 있음
        log.info("정산 스냅샷 생성. snapshotId={}, jobExecutionId={}, 대상 건수={}", snapshot.getId(), jobExecutionId, items.size());

        // 이번 조회 기간에서 잠긴 주문(#195) 때문에 빠진 후보가 있는 날만 남김: 잠금 때문에 정산되지 않은 후보가 있다는 것을 로그로 바로 알 수 있게
        // (0건이면 매일 같은 로그가 남아 생략)
        // 조회 기간 안의 후보만 세므로 누적 잠금 건수가 아님: 기간이 지난 후보는 다음 날부터 이 수에서 빠짐 (누적은 실패 기록의 잠금 사유로 확인)
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
    // 금액 검사에 걸린 주문은 잠김(AMOUNT_MISMATCH) → 다음 배치부터 그 주문의 후보는 스냅샷에 들어가지 않음
    // 잠금은 이후 Step이 실패해 Job이 FAILED로 끝나도 유지됨 (입력 금액에 대한 판정이라 재실행해도 다시 잠김)
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
        List<PayoutFailure> amountFailures = PayoutAmountValidator.validate(snapshotId, new PayoutAmountValidator.Input(
                payoutQueryRepository.findCandidatesWithDeduction(snapshotId),
                payoutQueryRepository.sumCandidateAmountByOrderInSnapshot(snapshotId),
                payoutQueryRepository.findDeductionAmountByOrderInSnapshot(snapshotId),
                payoutQueryRepository.findOriginalSaleCandidatesInSnapshot(snapshotId)));
        failures.addAll(amountFailures);

        payoutFailureRepository.saveAll(failures);

        logLockedOrders(snapshotId, amountFailures);
        // 팀 로그 규칙(service.md "로그"): 업무상 의미 있는 사건이라 INFO. 빠진 후보가 있는 날을 로그로 바로 찾을 수 있게 함
        log.info("정산 입력 검증. snapshotId={}, 차감 기록 없어 제외한 후보 수={}, 금액 검사로 제외한 후보 수={}",
                snapshotId, missing.size(), amountFailures.size());
        return failures.size();
    }

    // 주문이 새로 잠길 때마다 WARN: 잠긴 주문은 저절로 풀리지 않아(TODO(#52)) 사람이 원인을 확인해야 함
    // 판매자 수를 남기는 이유: 한 판매자의 금액이 틀려도 같은 주문의 다른 판매자 후보까지 함께 보류되어, 영향받은 판매자를 바로 알 수 있게
    // 금액 검사 실패 목록은 주문별로 이어서 들어옴 (PayoutAmountValidator가 주문 단위로 만듦)
    private void logLockedOrders(Long snapshotId, List<PayoutFailure> amountFailures) {
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
                // 사유는 원인 후보(AMOUNT_MISMATCH)의 상세로 남김 (함께 보류된 ORDER_LOCKED 상세는 원인이 아님)
                if (cause == null && failure.getReason() == PayoutFailureReason.AMOUNT_MISMATCH) {
                    cause = failure.getDetail();
                }
            }
            log.warn("정산 주문 잠금. snapshotId={}, orderId={}, 사유={}, 판매자 수={}, 후보 수={}",
                    snapshotId, entry.getKey(), cause, sellerIds.size(), entry.getValue().size());
        }
    }

    // 스냅샷에서 판매자 limit명 단위로 임시 정산(draft)을 만들고, 이번 회차 판매자 수(실패 포함)와 실패 수를 반환
    // 판매자 수가 0이면 더 없음 → tasklet 종료
    // 판매 대금·배송비는 판매자별 draft로, 수수료는 시스템 draft 항목으로만 저장 (합계는 commitStep에서 스냅샷 전체를 합침)
    // payout 생성(commitStep)과 이벤트 발행(publishStep)은 검증 이후 단계에서 함
    //
    // 판매자 단위 격리(#90): 판매자마다 저장 트랜잭션을 따로 둠 (PayoutSellerDraftService, REQUIRES_NEW)
    //   한 판매자에서 예외가 나면 그 판매자 것만 롤백하고, 후보 전체를 UNEXPECTED_ERROR로 기록한 뒤 다음 판매자로 넘어감
    //   → 같은 회차의 다른 판매자와 이후 회차는 계속 처리되고, 일부만 실패하면 Job도 멈추지 않음 (전에는 회차 전체가 롤백되고 Job이 FAILED)
    //   실패로 기록된 후보는 다음 회차의 조회 조건(조건 ④)에서 빠지므로, 실패한 판매자를 다시 뽑는 무한 반복은 없음
    //   실패 판매자는 판매자 몫과 수수료가 함께 롤백되어 commitStep에서 자연히 빠짐 (판매자 정산·시스템 몫 수수료 모두 없음)
    // 판매자 수에는 실패한 판매자도 셈: 0이 아니면 tasklet이 다음 회차를 부르고, 남은 판매자가 없을 때 0이 됨
    // 실패 수는 따로 돌려줘 배치 실행 기록(write skip count)에 남김: 일부 판매자가 실패해도 Job은 COMPLETED라 로그 말고도 볼 곳이 있어야 함
    //   일부만 실패하면 Job은 COMPLETED → 실패 수·WARN 로그로 확인 (반복 실패 알림은 #52)
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
                log.warn("정산 draft 판매자 처리 실패, 이 판매자만 제외하고 계속 진행. snapshotId={}, sellerId={}, 후보 수={}",
                        snapshotId, sellerId, sellerCandidates.size(), e);
                // 실패 기록 저장마저 실패하면 예외가 그대로 나가 Job이 멈춤
                // 기록 없이 넘어가면 그 후보가 draft에도 실패에도 없어 verify가 "draft 누락"으로 그 주문들을 잠금 (#290)
                //   → 일시적 오류였어도 관리자가 풀어야 하므로, 여기서 멈추는 편이 원인이 분명하고 뒷수습도 적음
                // TODO(#52): 실패 기록 저장마저 실패할 때의 처리는 고민 중 (지금은 위 이유로 Job을 멈춤)
                //   다른 방법 후보: 실패 기록 저장 재시도, 기록 없이 그 판매자만 건너뛰고 verify 규칙에서 따로 허용 등 → 어떻게 할지 #52에서 정함
                payoutSellerDraftService.recordUnexpectedError(snapshotId, sellerCandidates, e);
                failedSellerCount++;
            }
        }
        return new PayoutDraftResult(sellerIds.size(), failedSellerCount);
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

        // 팀 로그 규칙(service.md "로그"): INFO는 업무상 의미 있는 사건에 남기고, 식별자는 key={} 형태로 넣음
        // 정산 확정은 돈이 장부에 기록되는 시점이라 INFO로 남김
        log.info("정산 확정. snapshotId={}, payout 건수={} (시스템 몫 {}건), 결과 검증 실패로 뺀 draft 항목 수={}",
                snapshotId, payouts.size(), systemItems.isEmpty() ? 0 : 1, excludedItemCount);
    }

    // 이번 스냅샷에서 확정된 판매자 몫 정산만 지갑 이벤트로 발행하고(0원 이하 제외), 발행 건수를 반환
    // 시스템 몫은 정산 테이블에만 기록하고 지갑으로 보내지 않음 (지갑 담당자와 협의 완료)
    // 수신은 커밋 후 → 이 트랜잭션이 롤백되면 전달 안 됨. 같은 정산이 두 번 전달돼도 지갑은 payoutId로 중복 반영을 막음
    //
    // TODO(#52): 발행 누락 위험. commitStep은 성공했는데 publishStep이 실패하면 확정된 정산의 이벤트가 영원히 발행되지 않음
    //   예) commitStep ✅ payout 커밋 → publishStep ❌ (DB 연결 끊김 등) → Job FAILED
    //       → 다음 배치의 스냅샷은 "payout_item에 없는 후보"만 대상이라 이미 확정된 이 정산은 다시 다뤄지지 않음
    //       → 판매자는 정산이 확정됐는데 지갑에 돈이 들어오지 않음
    //   지금 구조는 실패한 Job을 재시작(restart)하지 않고 새로 재실행하므로 이 publishStep만 다시 돌릴 경로가 없음
    //   누락 찾는 방법: payout_snapshot.job_execution_id로 BATCH_STEP_EXECUTION에서 payoutPublishStep이 COMPLETED가 아닌 실행을 찾고,
    //   그 스냅샷의 판매자 몫 payout을 다시 발행 (지갑은 payoutId로 중복 반영을 막아야 함)
    //   해결 방향(후속 이슈에서 결정):
    //     - 발행 누락 정산(이벤트 발행 기록이 없는 판매자 몫 payout)을 찾아 다시 발행하는 경로 추가, 또는
    //     - 이벤트 발행을 commitStep 트랜잭션 안으로 옮김 (Spring Modulith가 발행 기록(event_publication)을 같은 트랜잭션에 저장하므로
    //       payout과 발행 기록이 함께 커밋·롤백되어 누락이 생기지 않음. 대신 publishStep이 없어짐)
    //   현재는 이슈 #69 설계(확정과 발행을 Step으로 분리)를 따름
    //   Kafka 전환 때 발행 방식(아웃박스 등)이 바뀌므로 그때 함께 처리 (#52에 기록)
    @Transactional
    public int publish(Long snapshotId) {
        List<Payout> payouts = payoutRepository.findBySnapshotIdAndSystemFalse(snapshotId);
        int published = 0;
        for (Payout payout : payouts) {
            // 0원 이하 정산은 지갑 이벤트를 보내지 않음 (#106, 지갑 쪽 요청)
            //   0원: 지갑에 반영할 금액이 없음
            //   음수: 아직 음수(환불·차감) 처리를 하지 않아 지갑이 받을 수 없음 → 환불 작업 때 넘기는 방법을 다시 정함
            // 정산(payout) 자체는 장부 기록으로 그대로 남김
            if (payout.getAmount() <= 0) {
                continue;
            }
            payoutEventPublisher.publishSellerPayoutCreated(new SellerPayoutCreatedCommand(
                    payout.getId(),
                    payout.getPayeeId(),
                    payout.getAmount(),
                    payout.getCreatedAt()
            ));
            published++;
        }

        // 팀 로그 규칙(service.md "로그"): INFO는 업무상 의미 있는 사건에 남기고, 식별자는 key={} 형태로 넣음
        // 지갑으로 돈이 넘어가기 시작하는 시점이라 INFO로 남김. 발행 누락을 추적할 때 이 로그가 없는 snapshotId를 찾으면 됨
        log.info("정산 지갑 이벤트 발행. snapshotId={}, 발행 건수={}, 0원 이하라 제외한 건수={}",
                snapshotId, published, payouts.size() - published);
        return published;
    }

    // 결과 검증(verifyStep): 스냅샷과 draft를 후보 단위로 비교해, 어긋난 후보의 주문을 이번 정산에서 빼고 잠금 (#290)
    // 나머지 주문은 commitStep으로 넘어가 정산됨 (전에는 하나라도 어긋나면 그날 정산 전체가 멈춤)
    // 판정 규칙은 PayoutDraftValidator, 빼는 기준은 PayoutVerifyExclusion 참고
    //
    // 순서
    //   1) 코드 고장 검사 → 걸리면 아무것도 기록하지 않고 전용 에러 코드로 Job 전체 중단
    //      판매자 합계 불일치, 실패 후보가 draft에 섞임: 모든 판매자가 같이 쓰는 코드가 망가진 것이라 일부만 빼도 나머지를 믿기 어려움
    //      기록·잠금을 먼저 남기지 않는 이유: 망가진 코드로 판정한 결과라 대량으로 잘못 잠글 수 있고, 고친 뒤 재실행하면 깨끗하게 다시 처리됨
    //   2) 후보 단위 검사 → 걸린 주문의 이번 스냅샷 후보(모든 판매자)를 VERIFY_MISMATCH로 기록 (주문 잠금)
    //      이미 이번 스냅샷에 실패 기록이 있는 후보는 건너뜀 (실패 기록 UK, 그 후보는 원래 draft에도 없음)
    //   3) 정산할 항목 수를 돌려줌 → 걸린 주문이 있는데 0건이면 tasklet이 Job을 FAILED로 끝냄 (PayoutJobConfig)
    //
    // 실패 기록은 별도 트랜잭션으로 저장 (PayoutVerifyFailureService): Job이 FAILED로 끝나도 기록·잠금이 남게
    //   그래서 이 메서드의 트랜잭션에서는 방금 저장한 기록이 다시 조회되지 않음 → 남은 항목 수는 저장한 목록으로 직접 계산
    // 이 메서드 자체는 조회만 함 (readOnly). verifyStep에서는 Step 트랜잭션(읽기·쓰기)에 참여해 그 설정을 따르지만 동작은 같음
    //   1)·2)의 조회가 같은 시점의 데이터를 봄: MySQL 기본 격리 수준(REPEATABLE READ)에서 한 트랜잭션 안의 조회는 같은 스냅샷을 읽음
    @Transactional(readOnly = true)
    public PayoutVerifyResult verify(Long snapshotId) {
        // 1) 코드 고장 검사
        // 팀 규칙(service.md "로그"): 예외를 던질 때 로그를 따로 남기지 않음 → 원인을 예외 메시지에 담아 배치 실패 기록(EXIT_MESSAGE)·로그에 한 번만 남김
        List<String> totalMismatches = PayoutDraftValidator.checkSellerTotals(payoutQueryRepository.findSellerTotalRows(snapshotId));
        if (!totalMismatches.isEmpty()) {
            // TODO(#52): 코드가 망가졌다는 것을 빨리 알리는 처리(슬랙 등 즉시 알림)가 필요. 지금은 전용 에러 코드로 Job만 멈춤
            throw new RestApiException(PayoutErrorCode.DRAFT_SELLER_TOTAL_MISMATCH, String.format(
                    "판매자 임시 정산 합계 불일치(코드 오류). snapshotId=%d, 불일치 판매자 수=%d, 내용(최대 %d건)=%s",
                    snapshotId, totalMismatches.size(), MESSAGE_ITEM_LIMIT, limit(totalMismatches)));
        }

        List<SnapshotItemRow> snapshotItems = payoutQueryRepository.findSnapshotItemRows(snapshotId);
        List<DraftItemRow> draftItems = new ArrayList<>(payoutQueryRepository.findSellerDraftItemRows(snapshotId));
        draftItems.addAll(payoutQueryRepository.findSystemDraftItemRows(snapshotId));
        List<FailureRow> failures = payoutQueryRepository.findFailureRows(snapshotId);

        PayoutDraftValidator.Result result = PayoutDraftValidator.validate(
                new PayoutDraftValidator.Input(snapshotItems, draftItems, failures));
        if (!result.failedCandidatesInDraft().isEmpty()) {
            // TODO(#52): 위와 같은 코드 고장 알림 필요
            throw new RestApiException(PayoutErrorCode.DRAFT_INCLUDES_FAILED_CANDIDATE, String.format(
                    "실패 후보가 임시 정산에 들어감(코드 오류). snapshotId=%d, 후보 수=%d, 후보 ID(최대 %d건)=%s",
                    snapshotId, result.failedCandidatesInDraft().size(), MESSAGE_ITEM_LIMIT,
                    limit(result.failedCandidatesInDraft())));
        }

        // 2) 걸린 주문의 후보를 실패로 기록 (주문 잠금)
        List<PayoutFailure> verifyFailures = toVerifyFailures(snapshotId, result.orderMismatches(), snapshotItems, failures);
        if (!verifyFailures.isEmpty()) {
            payoutVerifyFailureService.saveAll(verifyFailures);
        }
        logExcludedOrders(snapshotId, verifyFailures);

        // 3) commitStep이 정산할 항목 수 (commitStep과 같은 기준으로 셈)
        PayoutVerifyExclusion exclusion = PayoutVerifyExclusion.from(failures);
        exclusion.addAll(verifyFailures);
        int remainingItemCount = 0;
        for (DraftItemRow draftItem : draftItems) {
            if (!exclusion.excludes(draftItem.orderId(), draftItem.candidateId())) {
                remainingItemCount++;
            }
        }

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
        for (PayoutDraftValidator.OrderMismatch mismatch : mismatches) {
            String heldDetail = String.format(PayoutDraftValidator.HELD_DETAIL, mismatch.causes().keySet());
            for (SnapshotItemRow snapshotItem : snapshotItems) {
                if (snapshotItem.orderId().equals(mismatch.orderId()) && !details.containsKey(snapshotItem.candidateId())) {
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
    public void createAdjustment(Long adminId, PayoutAdjustmentCreateCommand command) {
        // TODO(논의 필요): 조정 번호(adjustmentId) 발급 방식(조정 요청 테이블에서 서버 발급 vs 클라이언트 전달)이 정해지지 않아 막아 둠
        throw new RestApiException(PayoutErrorCode.FEATURE_NOT_SUPPORTED,
                "정산 수동 조정은 아직 지원하지 않습니다. (orderItemId: " + command.orderItemId() + ")");
    }

    /**
     * 관리자가 정산 목록을 조회한다. 보낸 조건을 그대로 쓴다({@code payeeId}를 생략하면 전체).
     */
    @Transactional(readOnly = true)
    public Page<PayoutSummaryResult> searchForAdmin(PayoutSearchQuery query) {
        return searchPayouts(query.payeeId(), query.system(), query.from(), query.to(), query.page(), query.size());
    }

    /**
     * 판매자가 본인 정산 목록을 조회한다. 수취인은 본인, 대상은 판매자 몫(시스템 몫 제외)으로 고정한다.
     *
     * @param sellerId 요청한 판매자의 회원 ID (토큰에서 받은 값)
     */
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
    @Transactional(readOnly = true)
    public Page<PayoutItemResult> getItemsForSeller(Long sellerId, Long payoutId, PayoutItemSearchQuery query) {
        Payout payout = findPayout(payoutId);
        checkOwnPayout(sellerId, payout);
        return loadItems(payoutId, query);
    }

    // 관리자·판매자 항목 조회의 공통 부분
    private Page<PayoutItemResult> loadItems(Long payoutId, PayoutItemSearchQuery query) {
        return payoutQueryRepository.findItemsWithCandidate(payoutId, PageRequest.of(query.page(), query.size()))
                .map(PayoutItemResult::from);
    }

    /**
     * 관리자가 정산후보 목록을 조회한다. {@code sellerId}를 생략하면 전체 판매자, {@code unsettledOnly}면 아직 정산 전인 후보만 조회한다.
     */
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
                .map(PayoutCandidateResult::from);
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
