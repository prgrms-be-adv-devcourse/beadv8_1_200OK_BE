package com.ok.payout.service;

import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.repository.PayoutQueryRepository.DraftItemRow;
import com.ok.payout.repository.PayoutQueryRepository.FailureRow;
import com.ok.payout.repository.PayoutQueryRepository.SellerTotalRow;
import com.ok.payout.repository.PayoutQueryRepository.SnapshotItemRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// verifyStep 결과 검증 판정 (#290). DB 조회·저장은 PayoutService.verify가 하고, 여기는 받은 값으로 판정만 함
// validateStep의 PayoutAmountValidator와 역할이 다름:
//   PayoutAmountValidator(입력 검증) = 들어온 금액이 맞는지 → 틀린 주문만 빼고 잠금
//   여기(결과 검증) = 입력 검증을 통과한 데이터로 우리 코드가 draft를 제대로 만들었는지 → 틀리면 코드 버그
//
// 판정은 두 갈래 (어긋났을 때 무엇을 믿을 수 있는지가 달라서)
//   1) 코드 자체가 망가진 경우 → Job 전체 중단 (판매자 합계 불일치, 실패 후보가 draft에 섞임)
//      모든 판매자가 같이 쓰는 코드(합계 계산, draft 대상 조회 조건)가 틀린 것이라, 일부 주문만 빼도 나머지를 믿기 어려움
//   2) 특정 후보가 어긋난 경우 → 그 후보의 주문 전체를 빼고 잠금, 나머지 주문은 정산
//      후보 단위로 짝을 맞춰 보므로 통과한 후보는 금액·판매자·주문까지 하나씩 확인된 것
//
// 같은 주문 전체를 빼는 이유: 상품 1건이 빠졌는데 같은 주문의 배송비만 내보내는 일이 없게 (#195 금액 잠금과 같은 주문 단위)
final class PayoutDraftValidator {

    // 원인 후보의 상세. 함께 보류된 후보와 구분되게 접두어를 고정함 (사유는 둘 다 VERIFY_MISMATCH라 상세로만 구분됨)
    static final String CAUSE_PREFIX = "[원인] ";
    // 같은 주문이라 함께 빠진 후보의 상세 (이 후보 자체에는 문제가 없음)
    static final String HELD_DETAIL = "[함께 보류] 같은 주문의 결과 검증 불일치 (원인 후보 %s)";

    // 판정에 쓰는 값 묶음 (모두 이번 스냅샷의 PayoutQueryRepository 조회 결과)
    record Input(
            List<SnapshotItemRow> snapshotItems,   // 스냅샷 항목 = 원래 처리해야 할 후보
            List<DraftItemRow> draftItems,         // draft 항목 (판매자 몫 + 시스템 몫)
            List<FailureRow> failures              // 이번 스냅샷 실패 기록 (validate·draft·이전 verify)
    ) {
    }

    // 주문 하나를 빼는 판정 결과 (로그·실패 기록용)
    //   causes: 원인 후보 ID → 어긋난 내용 (여러 가지면 이어 붙임)
    record OrderMismatch(Long orderId, Map<Long, String> causes) {
    }

    // 판정 결과
    //   failedCandidatesInDraft: 실패 기록이 있는데 draft에 들어간 후보 (비어 있지 않으면 코드 고장 → 전체 중단)
    //   orderMismatches: 이번에 새로 걸린 주문 (이미 이전 verify로 빠진 주문은 다시 판정하지 않음)
    record Result(List<Long> failedCandidatesInDraft, List<OrderMismatch> orderMismatches) {
    }

    private PayoutDraftValidator() {
    }

    // 판매자 합계(헤더) 검사: 헤더 금액 ≠ 그 헤더 항목 합계인 판매자의 설명 목록 (비어 있으면 통과)
    //   예) 판매자 7 헤더 190,000 / 항목 합계 200,000 → "판매자 7: 헤더 190000, 항목 합계 200000"
    // 헤더는 같은 코드(PayoutSellerDraft.create)가 같은 순간에 항목을 더해 만드는 값이라, 다르면 코드가 망가진 것
    // 지급 금액은 commitStep이 항목을 다시 더해 만들어 틀리지 않지만, 코드 사고를 빨리 드러내려고 전체를 멈춤
    // 판매자 합계 한 줄이라 어느 주문 때문인지 알 수 없어 주문 단위로 뺄 수도 없음
    static List<String> checkSellerTotals(List<SellerTotalRow> totals) {
        List<String> mismatches = new ArrayList<>();
        for (SellerTotalRow total : totals) {
            if (!total.headerAmount().equals(total.itemAmount())) {
                mismatches.add("판매자 " + total.sellerId() + ": 헤더 " + total.headerAmount()
                        + ", 항목 합계 " + total.itemAmount());
            }
        }
        return mismatches;
    }

    // 후보 단위 검사
    // 스냅샷 후보마다: draft에 정확히 1번, 같은 금액·주문·판매자·타입으로, 맞는 몫(판매자/시스템)에 들어갔는지, 또는 실패 기록이 있는지
    // draft 항목마다: 스냅샷에 있는 후보인지
    // 이전 verify로 이미 빠진 주문(VERIFY_MISMATCH 기록이 있는 주문)은 다시 보지 않음
    //   그 주문의 후보는 draft에도 있고 실패 기록에도 있어서 다시 보면 매번 "실패 후보가 draft에 섞임"으로 걸림
    //   → 같은 스냅샷을 다시 검증해도 새로 걸리는 것이 없음 (운영은 재실행마다 새 스냅샷이라 테스트·수동 호출용 성질)
    static Result validate(Input input) {
        Map<Long, PayoutFailureReason> failureByCandidate = new LinkedHashMap<>();
        Set<Long> verifiedOrders = new LinkedHashSet<>();
        for (FailureRow failure : input.failures()) {
            failureByCandidate.put(failure.candidateId(), failure.reason());
            if (failure.reason() == PayoutFailureReason.VERIFY_MISMATCH) {
                verifiedOrders.add(failure.orderId());
            }
        }
        Map<Long, List<DraftItemRow>> draftsByCandidate = groupByCandidate(input.draftItems());

        List<Long> failedInDraft = new ArrayList<>();
        Map<Long, Map<Long, String>> causesByOrder = new LinkedHashMap<>();
        Set<Long> snapshotCandidates = new LinkedHashSet<>();

        for (SnapshotItemRow snapshot : input.snapshotItems()) {
            snapshotCandidates.add(snapshot.candidateId());
            if (verifiedOrders.contains(snapshot.orderId())) {
                continue;
            }
            PayoutFailureReason failure = failureByCandidate.get(snapshot.candidateId());
            List<DraftItemRow> drafts = draftsByCandidate.getOrDefault(snapshot.candidateId(), List.of());

            // 실패 기록이 있는 후보: draft에 없어야 정상 (draft 대상 조회 조건 ④가 빼야 함)
            // VERIFY_MISMATCH 기록은 여기까지 오지 않음: 실패 기록의 주문은 후보에서 복사하므로 그 주문은 위에서 이미 건너뜀
            if (failure != null) {
                if (!drafts.isEmpty()) {
                    failedInDraft.add(snapshot.candidateId());
                }
                continue;
            }

            if (drafts.isEmpty()) {
                addCause(causesByOrder, snapshot.orderId(), snapshot.candidateId(), "draft 누락");
                continue;
            }
            if (drafts.size() > 1) {
                addCause(causesByOrder, snapshot.orderId(), snapshot.candidateId(), "판매자 몫·시스템 몫에 중복");
            }
            for (DraftItemRow draft : drafts) {
                checkDraftMatchesSnapshot(snapshot, draft, causesByOrder);
            }
        }

        // 스냅샷에 없는 draft 항목: 이번 정산 대상이 아닌 후보가 draft에 들어감
        // 실패 기록이 있는 후보면 위와 같은 코드 고장, 아니면 그 항목의 주문을 뺌
        //   이 후보의 실패 기록은 후보의 원래 주문으로 남아 그 주문이 잠김 (실패 기록의 주문은 후보에서 복사)
        //   draft에 적힌 주문은 이번 스냅샷에 그 주문의 다른 후보가 있을 때만 잠김 (checkDraftMatchesSnapshot의 주문 다름과 같음)
        for (DraftItemRow draft : input.draftItems()) {
            if (snapshotCandidates.contains(draft.candidateId()) || verifiedOrders.contains(draft.orderId())) {
                continue;
            }
            PayoutFailureReason failure = failureByCandidate.get(draft.candidateId());
            if (failure == PayoutFailureReason.VERIFY_MISMATCH) {
                continue;
            }
            if (failure != null) {
                failedInDraft.add(draft.candidateId());
                continue;
            }
            addCause(causesByOrder, draft.orderId(), draft.candidateId(), "스냅샷에 없는 항목");
        }

        List<OrderMismatch> orderMismatches = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, String>> order : causesByOrder.entrySet()) {
            orderMismatches.add(new OrderMismatch(order.getKey(), order.getValue()));
        }
        return new Result(failedInDraft, orderMismatches);
    }

    // 스냅샷 후보와 그 후보의 draft 항목 1건 비교. 금액뿐 아니라 판매자·주문·몫까지 봄
    //   금액만 보면 다른 판매자 헤더 아래로 들어간 항목(돈이 다른 판매자에게 감)이나 수수료가 판매자 몫에 들어간 경우를 못 잡음
    // 주문이 다르면 두 주문 모두 뺌: commitStep은 draft 항목의 주문으로 거르므로 draft 쪽 주문도 빠져야 함
    //   단 draft 쪽 주문은 이번 스냅샷에 그 주문의 다른 후보가 있을 때만 실패 기록(= 잠금)이 생김 (실패 기록의 주문은 후보에서 복사하므로)
    //   다른 후보가 없으면 잠기지 않지만 정산할 것도 없고, 잘못 묶인 항목 자체는 후보 기준으로 빠짐 (PayoutVerifyExclusion)
    private static void checkDraftMatchesSnapshot(SnapshotItemRow snapshot, DraftItemRow draft,
                                                  Map<Long, Map<Long, String>> causesByOrder) {
        Long orderId = snapshot.orderId();
        Long candidateId = snapshot.candidateId();
        if (!snapshot.amount().equals(draft.amount())) {
            addCause(causesByOrder, orderId, candidateId,
                    "금액 다름(스냅샷 " + snapshot.amount() + ", draft " + draft.amount() + ")");
        }
        if (!snapshot.orderId().equals(draft.orderId())) {
            String cause = "주문 다름(스냅샷 " + snapshot.orderId() + ", draft " + draft.orderId() + ")";
            addCause(causesByOrder, orderId, candidateId, cause);
            addCause(causesByOrder, draft.orderId(), candidateId, cause);
        }
        if (!snapshot.sellerId().equals(draft.sellerId())) {
            addCause(causesByOrder, orderId, candidateId,
                    "판매자 다름(후보 " + snapshot.sellerId() + ", draft " + draft.sellerId() + ")");
        }
        if (snapshot.eventType() != draft.eventType()) {
            addCause(causesByOrder, orderId, candidateId,
                    "타입 다름(후보 " + snapshot.eventType() + ", draft " + draft.eventType() + ")");
        }
        // 시스템 몫 판단은 PayoutCandidateItem.isSystemShare()와 같은 기준 (SALE_FEE만 시스템 몫). 이벤트 타입이 추가되면 함께 수정
        boolean systemShare = snapshot.eventType() == PayoutEventType.SALE_FEE;
        if (systemShare != draft.systemShare()) {
            addCause(causesByOrder, orderId, candidateId, systemShare ? "수수료가 판매자 몫에 들어감" : "판매자 몫이 시스템 몫에 들어감");
        }
        // 판매자 몫은 묶인 헤더의 판매자가 수취인이 되므로, 후보의 판매자와 같아야 함
        if (!draft.systemShare() && !snapshot.sellerId().equals(draft.headerSellerId())) {
            addCause(causesByOrder, orderId, candidateId,
                    "다른 판매자 정산에 묶임(후보 " + snapshot.sellerId() + ", 헤더 " + draft.headerSellerId() + ")");
        }
    }

    // 주문별 원인 후보에 어긋난 내용을 더함 (같은 후보가 여러 검사에 걸리면 이어 붙임)
    private static void addCause(Map<Long, Map<Long, String>> causesByOrder, Long orderId, Long candidateId, String cause) {
        if (!causesByOrder.containsKey(orderId)) {
            causesByOrder.put(orderId, new LinkedHashMap<>());
        }
        Map<Long, String> causes = causesByOrder.get(orderId);
        if (causes.containsKey(candidateId)) {
            causes.put(candidateId, causes.get(candidateId) + ", " + cause);
        } else {
            causes.put(candidateId, cause);
        }
    }

    // draft 항목을 후보별로 묶음 (한 후보가 판매자 몫·시스템 몫 양쪽에 있으면 2건)
    private static Map<Long, List<DraftItemRow>> groupByCandidate(List<DraftItemRow> draftItems) {
        Map<Long, List<DraftItemRow>> byCandidate = new LinkedHashMap<>();
        for (DraftItemRow draft : draftItems) {
            if (!byCandidate.containsKey(draft.candidateId())) {
                byCandidate.put(draft.candidateId(), new ArrayList<>());
            }
            byCandidate.get(draft.candidateId()).add(draft);
        }
        return byCandidate;
    }
}
