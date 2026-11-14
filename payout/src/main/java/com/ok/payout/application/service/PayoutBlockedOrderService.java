package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutBlockedOrderResult;
import com.ok.payout.application.port.in.PayoutBlockedOrderSearchQuery;
import com.ok.payout.application.port.in.PayoutBlockedOrderUseCase;
import com.ok.payout.application.port.out.BlockedOrderRow;
import com.ok.payout.application.port.out.OverchargeCandidateRow;
import com.ok.payout.application.port.out.PayoutBlockedOrderRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.PayoutBlockedOrderCause;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutOverchargeEvidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// 막힌 주문 집계 표(#582) 갱신의 순서(대상 주문 고르기 → 묶음마다 다시 판정 → 반영 표시 올리기)와 관리자 목록 조회(#569)를 맡음
// 묶음마다 트랜잭션을 따로 두려고 실제 판정·저장은 PayoutBlockedOrderRefreshService에 둠
//   같은 클래스 안에서 부르면 프록시를 거치지 않아 @Transactional이 무시됨 (PayoutSellerDraftService와 같은 이유)
// 이 클래스에는 @Transactional을 두지 않음: 막힌 주문이 많을 때 하나의 긴 트랜잭션으로 묶지 않으려고
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutBlockedOrderService implements PayoutBlockedOrderUseCase {

    // 한 트랜잭션에서 다시 판정할 주문 수: IN 목록 길이와 트랜잭션 길이를 함께 묶어 둠
    static final int REFRESH_CHUNK_SIZE = 1_000;

    private final PayoutQueryRepository payoutQueryRepository;
    private final PayoutBlockedOrderRepository payoutBlockedOrderRepository;
    private final PayoutBlockedOrderRefreshService payoutBlockedOrderRefreshService;

    // 대상 = 지금 표에 있는 주문 ∪ 반영 표시 뒤에 생긴 실패 기록의 주문
    //   표에 있는 주문: 해결(#568)로 풀렸으면 지워야 함. 잠기거나 멈춘 주문은 다음 스냅샷에 안 들어가 새 실패 기록이 없으므로 따로 넣음
    //   "이번 스냅샷"이 아니라 반영 표시 뒤를 보는 이유: 갱신을 한 번 놓쳐도(갱신 실패, 서버 다운) 다음 갱신이 이어서 잡게
    // 상한(upTo)을 먼저 정함: 갱신 중에 새 실패 기록이 생겨도 그 기록은 다음 갱신이 봄 (지금은 Job끼리 ShedLock으로 겹치지 않음)
    // 전제: 실패 기록은 정산 Job 안(PayoutService·PayoutSellerDraftService, Job 단계)에서만 생기고, 이 갱신은 Job이 끝난 뒤 같은 ShedLock 안에서 돎
    //   → 갱신이 읽는 시점에 반영 표시 뒤의 실패 기록은 모두 커밋돼 있음. 실패 기록을 Job 밖(관리자 API 등)에서 만들면 이 전제가 깨짐:
    //     AUTO_INCREMENT 번호 순서와 커밋 순서가 달라, 아직 커밋 안 된 작은 번호를 반영 표시가 지나쳐 영영 못 볼 수 있음 (PR #585 리뷰)
    // 묶음 하나가 실패하면 예외가 그대로 나가 반영 표시를 올리지 않음 → 다음 갱신이 같은 범위를 처음부터 다시 판정함
    //   다시 판정은 같은 원본이면 같은 결과라, 앞서 끝난 묶음을 다시 해도 결과가 같음
    @Override
    public void refresh() {
        long afterId = payoutBlockedOrderRefreshService.lastReflectedFailureId();
        // 가장 큰 실패 기록 ID가 반영 표시보다 작으면 반영 표시를 그대로 둠
        //   실패 기록은 지우지 않지만, 사람이 최근 기록을 DB에서 직접 지우면 생길 수 있음. 반영 표시를 뒤로 돌리려다 매번 갱신이 실패하지 않게 함
        //   (AUTO_INCREMENT는 지워도 되돌아가지 않아, 다음 실패 기록은 반영 표시보다 큰 번호를 받음)
        long upToId = Math.max(afterId, payoutQueryRepository.findMaxFailureId());

        Set<Long> targetOrderIds = new TreeSet<>(payoutBlockedOrderRepository.findAllOrderIds());
        targetOrderIds.addAll(payoutQueryRepository.findOrderIdsWithFailuresBetween(afterId, upToId));

        List<Long> orderIds = new ArrayList<>(targetOrderIds);
        for (int from = 0; from < orderIds.size(); from += REFRESH_CHUNK_SIZE) {
            int to = Math.min(from + REFRESH_CHUNK_SIZE, orderIds.size());
            payoutBlockedOrderRefreshService.refreshOrders(new ArrayList<>(orderIds.subList(from, to)));
        }
        payoutBlockedOrderRefreshService.markReflected(upToId);

        log.info("막힌 주문 표 갱신 끝. 다시 판정한 주문 수={}, 반영한 실패 기록 ID={}~{}",
                orderIds.size(), afterId, upToId);
    }

    // 관리자 막힌 주문 목록 (#569): 한 페이지의 막힌 주문을 읽고, 그 주문들의 원인을 한 번에 읽어 붙임
    // 읽기만 하므로 이 메서드에만 읽기 전용 트랜잭션 (클래스에는 두지 않음, 위 주석)
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutBlockedOrderResult> getBlockedOrders(PayoutBlockedOrderSearchQuery query) {
        Page<BlockedOrderRow> page = payoutQueryRepository.findBlockedOrders(PageRequest.of(query.page(), query.size()));
        List<Long> orderIds = new ArrayList<>();
        for (BlockedOrderRow row : page.getContent()) {
            orderIds.add(row.orderId());
        }
        Map<Long, List<PayoutBlockedOrderCause>> causesByOrder = new HashMap<>();
        for (PayoutBlockedOrderCause cause : payoutQueryRepository.findBlockedOrderCauses(orderIds)) {
            causesByOrder.computeIfAbsent(cause.getOrderId(), id -> new ArrayList<>()).add(cause);
        }
        Map<Long, PayoutOverchargeEvidence> evidenceNowByOrder = currentOverchargeEvidence(causesByOrder);
        return page.map(row -> PayoutQueryResultMapper.toBlockedOrderResult(
                row, causesByOrder.getOrDefault(row.orderId(), List.of()), evidenceNowByOrder.get(row.orderId())));
    }

    // "지금" 초과 청구 근거 (#570): 원인에 초과 청구가 있는 주문만, 볼 때의 원본으로 계산 (주문 ID → 근거)
    // 금액 검사와 같은 계산(PayoutAmountValidator.overchargeEvidence)과 같은 범위(조정 제외 정산후보 전체)를 써서
    //   "막힌 순간" 값과 나란히 놓으면 무엇이 바뀌었는지 보임
    // 한 페이지 주문 ID로 묶어 세 번만 조회함 (주문마다 따로 조회하지 않음, payout/CLAUDE.md 설계 전제)
    // 근거를 계산할 수 없는 주문은 결과에 없음(목록에서 null): 차감 기록이 없거나, 조정만 있어 합계가 없는 주문
    private Map<Long, PayoutOverchargeEvidence> currentOverchargeEvidence(
            Map<Long, List<PayoutBlockedOrderCause>> causesByOrder) {
        List<Long> overchargedOrderIds = new ArrayList<>();
        for (Map.Entry<Long, List<PayoutBlockedOrderCause>> order : causesByOrder.entrySet()) {
            for (PayoutBlockedOrderCause cause : order.getValue()) {
                if (cause.getReason() == PayoutFailureReason.AMOUNT_MISMATCH) {
                    overchargedOrderIds.add(order.getKey());
                    break;
                }
            }
        }
        Map<Long, PayoutOverchargeEvidence> evidenceByOrder = new HashMap<>();
        if (overchargedOrderIds.isEmpty()) {
            return evidenceByOrder;
        }

        Map<Long, Long> deductions = payoutQueryRepository.findDeductionAmountsByOrderIds(overchargedOrderIds);
        Map<Long, List<OverchargeCandidateRow>> rowsByOrder = PayoutAmountValidator.groupRowsByOrder(
                payoutQueryRepository.findOverchargeCandidates(overchargedOrderIds));
        Map<Long, List<Long>> conflictAmounts =
                payoutQueryRepository.findReceivedConflictAmountsByOrder(overchargedOrderIds);
        for (Long orderId : overchargedOrderIds) {
            Long deducted = deductions.get(orderId);
            List<OverchargeCandidateRow> rows = rowsByOrder.get(orderId);
            if (deducted == null || rows == null) {
                continue;
            }
            // 합계는 근거 줄을 더해 냄: 금액 검사의 합계 조회와 같은 범위(조정 제외)라 따로 조회하지 않음
            long total = 0L;
            for (OverchargeCandidateRow row : rows) {
                total += row.amount();
            }
            evidenceByOrder.put(orderId, PayoutAmountValidator.overchargeEvidence(deducted, total, rows,
                    conflictAmounts.getOrDefault(orderId, List.of())));
        }
        return evidenceByOrder;
    }
}
