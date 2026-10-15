package com.ok.payout.service;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSnapshotItem;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSnapshotItemRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    // 검증 실패 시 원인 확인용으로 예외 메시지에 담는 누락 후보 ID 최대 개수 (메시지가 너무 길어지지 않게)
    // TODO(#67): 임시 처리. 정산 실패 기록이 생기면 누락 후보 전체를 실패 기록으로 남기고 이 제한은 정리
    private static final int MISSING_CANDIDATE_LOG_LIMIT = 100;

    private final PayoutQueryRepository payoutQueryRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutSnapshotRepository payoutSnapshotRepository;
    private final PayoutSnapshotItemRepository payoutSnapshotItemRepository;
    private final PayoutSellerDraftRepository payoutSellerDraftRepository;
    private final PayoutSystemDraftItemRepository payoutSystemDraftItemRepository;
    private final ApplicationEventPublisher eventPublisher;

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
        // jobExecutionId로 배치 실행 기록과, snapshotId로 이후 Step 로그(검증 실패 시 누락 목록 등)와 이어서 볼 수 있음
        log.info("정산 스냅샷 생성. snapshotId={}, jobExecutionId={}, 대상 건수={}", snapshot.getId(), jobExecutionId, items.size());
        return snapshot.getId();
    }

    // 스냅샷에서 판매자 limit명 단위로 임시 정산(draft)을 만들고, 처리한 판매자 수를 반환 (0이면 더 없음 → tasklet 종료)
    // 판매 대금·배송비는 판매자별 draft로, 수수료는 시스템 draft 항목으로만 저장 (합계는 commitStep에서 스냅샷 전체를 합침)
    // payout 생성(commitStep)과 이벤트 발행(publishStep)은 검증 이후 단계에서 함
    @Transactional
    public int draft(Long snapshotId, int limit) {
        List<Long> sellerIds = payoutQueryRepository.findUndraftedSellerIds(snapshotId, limit);
        if (sellerIds.isEmpty()) {
            return 0;
        }

        List<PayoutCandidateItem> candidates = payoutQueryRepository.findUndraftedCandidates(snapshotId, sellerIds);
        // 판매자는 뽑혔는데 후보가 0건 = 두 조회 조건 불일치(버그). 그대로 두면 같은 판매자가 계속 뽑혀 무한 반복되므로 실패시킴
        if (candidates.isEmpty()) {
            throw new RestApiException(PayoutErrorCode.UNSETTLED_CANDIDATES_NOT_FOUND);
        }

        // 정산후보 하나는 판매자 몫·시스템 몫 중 한 곳에만 들어감 (isSystemShare 기준)
        Map<Long, List<PayoutCandidateItem>> candidatesBySeller = new LinkedHashMap<>();
        List<PayoutSystemDraftItem> systemItems = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidates) {
            if (candidate.isSystemShare()) {
                systemItems.add(PayoutSystemDraftItem.create(snapshotId, candidate));
                continue;
            }
            Long sellerId = candidate.getSellerId();
            if (!candidatesBySeller.containsKey(sellerId)) {
                candidatesBySeller.put(sellerId, new ArrayList<>());
            }
            candidatesBySeller.get(sellerId).add(candidate);
        }

        List<PayoutSellerDraft> sellerDrafts = new ArrayList<>();
        for (Map.Entry<Long, List<PayoutCandidateItem>> entry : candidatesBySeller.entrySet()) {
            sellerDrafts.add(PayoutSellerDraft.create(snapshotId, entry.getKey(), entry.getValue()));
        }

        payoutSellerDraftRepository.saveAll(sellerDrafts);
        payoutSystemDraftItemRepository.saveAll(systemItems);
        return sellerIds.size();
    }

    // 검증을 통과한 draft를 payout·payout_item으로 확정
    // 판매자 draft 1건 → 판매자 몫 payout 1건, 시스템 draft 항목 전체 → 시스템 몫 payout 1건 (항목이 없으면 만들지 않음)
    //
    // 반드시 단일 트랜잭션 (분리 금지)
    // 시스템 몫(payee_id = NULL)은 payout UK(snapshot_id, payee_id)로 중복을 막지 못함 (MySQL은 NULL끼리 같은 값으로 보지 않음)
    //   예) 같은 스냅샷을 실수로 다시 commit → 시스템 몫 (12, NULL)이 또 저장되는 것은 UK가 못 막음
    // 대신 같은 트랜잭션 안에서 payout_item을 저장할 때 UK(candidate_id) 위반으로 실패 → 시스템 몫까지 전체 롤백되어 중복이 남지 않음
    // 트랜잭션을 나누면 시스템 몫이 먼저 커밋되어 중복이 조용히 남음
    @Transactional
    public void commit(Long snapshotId) {
        List<Payout> payouts = new ArrayList<>();
        for (PayoutSellerDraft draft : payoutSellerDraftRepository.findBySnapshotId(snapshotId)) {
            payouts.add(Payout.forSeller(snapshotId, draft));
        }

        List<PayoutSystemDraftItem> systemItems = payoutSystemDraftItemRepository.findBySnapshotId(snapshotId);
        if (!systemItems.isEmpty()) {
            payouts.add(Payout.forSystem(snapshotId, systemItems));
        }

        payoutRepository.saveAll(payouts);

        // 팀 로그 규칙(service.md "로그"): INFO는 업무상 의미 있는 사건에 남기고, 식별자는 key={} 형태로 넣음
        // 정산 확정은 돈이 장부에 기록되는 시점이라 INFO로 남김
        log.info("정산 확정. snapshotId={}, payout 건수={} (시스템 몫 {}건)", snapshotId, payouts.size(), systemItems.isEmpty() ? 0 : 1);
    }

    // 이번 스냅샷에서 확정된 판매자 몫 정산만 지갑 이벤트로 발행하고, 발행 건수를 반환
    // 시스템 몫은 정산 테이블에만 기록하고 지갑으로 보내지 않음 (지갑 담당자와 협의 완료)
    // 수신은 커밋 후 → 이 트랜잭션이 롤백되면 전달 안 됨. 같은 정산이 두 번 전달돼도 지갑은 payoutId로 중복 반영을 막음
    //
    // TODO(#67): 발행 누락 위험. commitStep은 성공했는데 publishStep이 실패하면 확정된 정산의 이벤트가 영원히 발행되지 않음
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
    @Transactional
    public int publish(Long snapshotId) {
        List<Payout> payouts = payoutRepository.findBySnapshotIdAndSystemFalse(snapshotId);
        for (Payout payout : payouts) {
            eventPublisher.publishEvent(new SellerPayoutCreatedEvent(
                    payout.getId(),
                    payout.getPayeeId(),
                    payout.getAmount(),
                    payout.getCreatedAt()
            ));
        }

        // 팀 로그 규칙(service.md "로그"): INFO는 업무상 의미 있는 사건에 남기고, 식별자는 key={} 형태로 넣음
        // 지갑으로 돈이 넘어가기 시작하는 시점이라 INFO로 남김. 발행 누락을 추적할 때 이 로그가 없는 snapshotId를 찾으면 됨
        log.info("정산 지갑 이벤트 발행. snapshotId={}, 발행 건수={}", snapshotId, payouts.size());
        return payouts.size();
    }

    // 스냅샷 목록과 draft 결과를 비교해, 어긋나면 예외로 Job을 멈춤 (payout 확정·이벤트 발행으로 넘어가지 않음)
    // 검증 실패한 draft는 지우지 않고 기록으로 남김. 다음 실행은 새 스냅샷으로 처음부터 처리
    //
    // 검증 규칙을 추가할 때 고치는 곳 (검증 규칙은 앞으로 늘어날 예정)
    //   1) 새 검사에 필요한 숫자가 있으면 → 아래 1단계 Input에 값 추가 (+ PayoutQueryRepository에 집계 쿼리 추가)
    //   2) 비교 규칙 자체 → PayoutDraftValidator에 check...() 메서드 추가 후 validate()에 한 줄 추가
    //   3) 이 메서드의 2·3단계는 보통 고치지 않음 (실패 사유는 Validator가 모아서 돌려줌)
    // readOnly 트랜잭션: 1단계 집계 쿼리들이 같은 시점의 데이터를 보도록 함 (읽기만 하므로 readOnly)
    @Transactional(readOnly = true)
    public void verify(Long snapshotId) {
        // 1) 비교할 숫자 모으기 (DB 집계)
        //    ↳ 검사 추가 시: 새로 필요한 숫자는 여기에 넣음 (PayoutDraftValidator.Input 필드도 함께 추가)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                payoutQueryRepository.summarizeSnapshotItems(snapshotId),
                payoutQueryRepository.summarizeSellerDraftItems(snapshotId),
                payoutQueryRepository.summarizeSystemDraftItems(snapshotId),
                payoutQueryRepository.sumSellerDraftAmount(snapshotId));

        // 2) 규칙 검사
        //    ↳ 검사 추가 시: 규칙은 여기가 아니라 PayoutDraftValidator에 추가
        List<String> failures = PayoutDraftValidator.validate(input);
        if (failures.isEmpty()) {
            log.info("정산 draft 검증 통과. snapshotId={}, 건수={}, 금액={}",
                    snapshotId, input.snapshotItems().count(), input.snapshotItems().amount());
            return;
        }

        // 3) 실패 처리
        // 팀 규칙(service.md "로그"): 예외를 던질 때 로그를 따로 남기지 않음
        // → 원인(실패 사유·누락 후보)을 예외 메시지에 담아, 배치 실패 기록(BATCH_STEP_EXECUTION)과 로그에 한 번만 남게 함
        // TODO(#67): 임시 처리. 지금은 누락 후보를 최대 100건만 메시지로 남김. 정산 실패 기록이 생기면 그쪽에 저장하도록 변경
        List<Long> missingIds = payoutQueryRepository.findUndraftedCandidateIds(snapshotId, MISSING_CANDIDATE_LOG_LIMIT);
        throw new RestApiException(PayoutErrorCode.DRAFT_VERIFICATION_FAILED, String.format(
                "정산 draft 검증 실패. snapshotId=%d, 실패 사유=%s, 누락 후보(최대 %d건)=%s",
                snapshotId, failures, MISSING_CANDIDATE_LOG_LIMIT, missingIds));
    }
}
