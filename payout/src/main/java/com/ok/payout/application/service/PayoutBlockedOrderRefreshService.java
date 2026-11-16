package com.ok.payout.application.service;

import com.ok.payout.application.port.out.BlockedFailureRow;
import com.ok.payout.application.port.out.PayoutBlockedOrderCauseRepository;
import com.ok.payout.application.port.out.PayoutBlockedOrderRefreshRepository;
import com.ok.payout.application.port.out.PayoutBlockedOrderRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.PayoutBlockedOrder;
import com.ok.payout.domain.PayoutBlockedOrderCause;
import com.ok.payout.domain.PayoutBlockedOrderRefresh;
import com.ok.payout.domain.PayoutBlockedStatus;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 막힌 주문 집계 표(#582)의 묶음 하나를 원본에서 다시 판정해 저장함 (순서는 PayoutBlockedOrderService)
// 판정 기준은 스냅샷·해결 API와 같음: 잠김 = lockingFailure(findLockedOrderIds), 멈춤 = PayoutPolicy.isStopped(실패 횟수)
//   막힘 = 잠김, 또는 잠기지 않고 멈춤. 잠김이면서 멈춤이면 LOCKED 한 줄 (잠김은 다시 검사해도 안 풀려 사람이 꼭 봐야 함)
// 단계: 원본 읽기 → 주문마다 판정(메모리 계산) → 저장. 판정과 저장을 나눈 이유는 아래 refreshOrders 주석
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutBlockedOrderRefreshService {

    private final PayoutQueryRepository payoutQueryRepository;
    private final PayoutBlockedOrderRepository payoutBlockedOrderRepository;
    private final PayoutBlockedOrderCauseRepository payoutBlockedOrderCauseRepository;
    private final PayoutBlockedOrderRefreshRepository payoutBlockedOrderRefreshRepository;

    // 표에 반영한 마지막 실패 기록 ID
    public long lastReflectedFailureId() {
        return getRefresh().getLastFailureId();
    }

    // 다 끝난 뒤에만 부름: 이 번호까지의 실패 기록을 표에 반영함
    @Transactional
    public void markReflected(long failureId) {
        getRefresh().advanceTo(failureId);
    }

    // 주문들을 원본으로 다시 판정: 막혔으면 넣기·고치기(원인은 지우고 다시 넣음), 풀렸으면 지우기
    // RESOLVED(해결됨)였던 줄도 여기서 정리됨: 풀렸으면 지우고, 해결 뒤 다시 막혔으면 LOCKED·STOPPED로 바꿈
    // 금액 검사가 넣은 PENDING(예비) 줄도 같음 (#570): 막혔으면 LOCKED·STOPPED로 바꾸고, 아직 막힘이 아니면(1·2번째 실패) 지움
    // 판정을 먼저 다 끝내고 저장하는 이유: 판정에서 예외가 난 주문 하나만 건너뛰고 나머지는 저장하려고 (PR #585 리뷰, 사용자 결정)
    //   판정은 DB를 고치지 않는 메모리 계산이라 예외가 나도 트랜잭션이 망가지지 않음. 건너뛴 주문의 줄·원인은 건드리지 않음
    //   저장 중 DB 오류는 트랜잭션이 이미 롤백 표시라 주문 하나만 뺄 수 없어 묶음 전체가 실패함 (반영 표시가 그대로라 다음 갱신이 다시 함)
    // 세 조회가 같은 시점을 보는 이유: 한 트랜잭션이고 MySQL 기본 격리 수준(REPEATABLE READ)이라, 사이에 해결이 커밋돼도 이 갱신은 그 전 상태로 일관되게 봄
    // 알고 두는 틈: 여기서 원본을 읽은 뒤 커밋 전에 해결 API가 그 줄을 RESOLVED로 바꾸면, 이 갱신이 예전 상태로 덮을 수 있음
    //   다음 갱신에 표에 있는 주문으로 다시 판정되어 바로잡힘. 하루 1번 Job과 관리자 조작이 겹치는 드문 경우라 잠금을 두지 않음 (사용자 결정)
    @Transactional
    public void refreshOrders(List<Long> orderIds) {
        Map<Long, JudgedOrder> judged = judgeOrders(orderIds);
        save(judged);
    }

    // 원본을 읽어 주문마다 판정. 판정에서 예외가 난 주문은 [정산-시스템문제]로 남기고 결과에서 뺌
    //   건너뛴 주문이 표에 없던 새 주문이면 반영 표시가 지나가 다시 보지 않음 → 버그를 고친 뒤 범위 맞추기(#583)로 바로잡음
    private Map<Long, JudgedOrder> judgeOrders(List<Long> orderIds) {
        Set<Long> lockedOrderIds = payoutQueryRepository.findLockedOrderIds(orderIds);
        Map<Long, Long> failedRunCounts = payoutQueryRepository.countFailedRunsOfOrders(orderIds);
        Map<Long, List<BlockedFailureRow>> failuresByOrder = groupByOrder(
                payoutQueryRepository.findUnresolvedFailureRowsOfOrders(orderIds));

        Map<Long, JudgedOrder> judged = new LinkedHashMap<>();
        for (Long orderId : orderIds) {
            try {
                judged.put(orderId, judge(orderId, lockedOrderIds.contains(orderId),
                        failedRunCounts.getOrDefault(orderId, 0L), failuresByOrder.getOrDefault(orderId, List.of())));
            } catch (RuntimeException e) {
                log.error("[정산-시스템문제] 막힌 주문 판정 실패. 이 주문은 이번 갱신에서 건너뜀. orderId={}", orderId, e);
            }
        }
        return judged;
    }

    // 판정 결과를 저장. 판정된 주문만 다룸 (건너뛴 주문은 원인도 지우지 않음)
    private void save(Map<Long, JudgedOrder> judged) {
        if (judged.isEmpty()) {
            return;
        }
        List<Long> orderIds = new ArrayList<>(judged.keySet());
        // 원인은 막힌 주문을 외래키로 가리켜 먼저 지움. 막힌 채인 주문은 아래에서 다시 넣음
        payoutBlockedOrderCauseRepository.deleteAllByOrderIdIn(orderIds);

        Map<Long, PayoutBlockedOrder> existing = new HashMap<>();
        for (PayoutBlockedOrder blockedOrder : payoutBlockedOrderRepository.findAllByOrderIdIn(orderIds)) {
            existing.put(blockedOrder.getOrderId(), blockedOrder);
        }

        List<PayoutBlockedOrder> newBlockedOrders = new ArrayList<>();
        List<PayoutBlockedOrderCause> causes = new ArrayList<>();
        List<Long> unblockedOrderIds = new ArrayList<>();
        for (JudgedOrder order : judged.values()) {
            PayoutBlockedOrder blockedOrder = existing.get(order.orderId());
            if (!order.blocked()) {
                if (blockedOrder != null) {
                    unblockedOrderIds.add(order.orderId());
                }
                continue;
            }
            if (blockedOrder == null) {
                newBlockedOrders.add(PayoutBlockedOrder.of(order.orderId(), order.status(), order.failedRunCount(),
                        order.lastFailedAt()));
            } else {
                // 이미 있는 줄은 변경 감지(트랜잭션이 끝날 때 바뀐 칸을 UPDATE)로 고침
                // 금액 검사가 넣은 예비(PENDING) 줄도 여기서 막힘 상태가 됨. 초과 청구 근거는 금액 검사 때 값이라 그대로 둠 (#570)
                blockedOrder.change(order.status(), order.failedRunCount(), order.lastFailedAt());
                // 원인에 초과 청구가 없으면 근거를 비움: 해결 뒤 다른 이유로 다시 막힌 줄에 예전 초과 청구 근거가 남지 않게 (#570)
                //   지금은 초과 청구가 한번 걸리면 금액을 고치기 전까지 계속 걸려 드묾. 조정(#291)이 열리면 생길 수 있음
                if (!hasOverchargeCause(order.causes())) {
                    blockedOrder.clearEvidence();
                }
            }
            causes.addAll(order.causes());
        }

        payoutBlockedOrderRepository.saveAll(newBlockedOrders);
        if (!unblockedOrderIds.isEmpty()) {
            payoutBlockedOrderRepository.deleteAllByOrderIdIn(unblockedOrderIds);
        }
        payoutBlockedOrderCauseRepository.saveAll(causes);
    }

    // 주문 하나 판정 (DB를 고치지 않는 계산). 막힘이 아니면 status가 null인 결과
    // 막혔는데 해결 안 된 실패 기록이 없으면 IllegalStateException: 잠김은 해결 안 된 잠그는 실패가, 멈춤은 해결 안 된 실패가 있어야 성립하므로
    //   비어 있으면 조회 조건이 서로 어긋난 것(코드 버그). 세 조회가 한 트랜잭션의 같은 시점을 보므로 동시 해결로는 생기지 않음
    static JudgedOrder judge(Long orderId, boolean locked, long failedRuns, List<BlockedFailureRow> failures) {
        PayoutBlockedStatus status = status(locked, failedRuns);
        if (status == null) {
            return JudgedOrder.unblocked(orderId);
        }
        if (failures.isEmpty()) {
            throw new IllegalStateException("막힌 주문인데 해결 안 된 실패 기록이 없습니다. orderId=" + orderId);
        }
        return new JudgedOrder(orderId, status, Math.toIntExact(failedRuns), lastFailedAt(failures),
                toCauses(orderId, failures));
    }

    // 막힘이 아니면 null
    private static PayoutBlockedStatus status(boolean locked, long failedRuns) {
        if (locked) {
            return PayoutBlockedStatus.LOCKED;
        }
        if (PayoutPolicy.isStopped(failedRuns)) {
            return PayoutBlockedStatus.STOPPED;
        }
        return null;
    }

    // 마지막 실패 시각은 함께 보류 기록까지 포함한 해결 안 된 실패 전부에서 (실행 성공 여부와 상관없음)
    private static LocalDateTime lastFailedAt(List<BlockedFailureRow> failures) {
        LocalDateTime last = failures.get(0).failedAt();
        for (BlockedFailureRow failure : failures) {
            if (failure.failedAt().isAfter(last)) {
                last = failure.failedAt();
            }
        }
        return last;
    }

    // 원인 = 함께 보류를 뺀 기록. 주문상품·종류·사유·상세가 같으면 1번만(가장 늦은 실패 시각으로)
    //   함께 보류: 수수료 불일치 때의 ORDER_HELD, 결과 검증 불일치 때 "[함께 보류]" 상세 (사유가 원인과 같은 VERIFY_MISMATCH라 상세로만 구분)
    //   멈춘 주문은 같은 실패가 실행마다 쌓여, 그대로 두면 같은 줄이 실행 수만큼 반복됨
    // 함께 보류만 남았으면(원인 기록이 이미 정산·해결로 빠진 경우 등) 그 기록이라도 보여 줌: 원인 칸이 비면 왜 막혔는지 알 수 없음
    private static List<PayoutBlockedOrderCause> toCauses(Long orderId, List<BlockedFailureRow> failures) {
        List<BlockedFailureRow> picked = new ArrayList<>();
        for (BlockedFailureRow failure : failures) {
            if (!isHeld(failure)) {
                picked.add(failure);
            }
        }
        if (picked.isEmpty()) {
            picked = failures;
        }

        Map<CauseKey, BlockedFailureRow> latestByKey = new LinkedHashMap<>();
        for (BlockedFailureRow failure : picked) {
            CauseKey key = new CauseKey(failure.orderItemId(), failure.eventType(), failure.reason(), failure.detail());
            BlockedFailureRow kept = latestByKey.get(key);
            if (kept == null || failure.failedAt().isAfter(kept.failedAt())) {
                latestByKey.put(key, failure);
            }
        }

        List<PayoutBlockedOrderCause> causes = new ArrayList<>();
        for (BlockedFailureRow failure : latestByKey.values()) {
            causes.add(PayoutBlockedOrderCause.of(orderId, failure.orderItemId(), failure.eventType(),
                    failure.reason(), failure.detail(), failure.sellerId(), failure.failedAt()));
        }
        return causes;
    }

    private static boolean hasOverchargeCause(List<PayoutBlockedOrderCause> causes) {
        for (PayoutBlockedOrderCause cause : causes) {
            if (cause.getReason() == PayoutFailureReason.AMOUNT_MISMATCH) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHeld(BlockedFailureRow failure) {
        return failure.reason() == PayoutFailureReason.ORDER_HELD
                || failure.detail().startsWith(PayoutDraftValidator.HELD_PREFIX);
    }

    private static Map<Long, List<BlockedFailureRow>> groupByOrder(List<BlockedFailureRow> failures) {
        Map<Long, List<BlockedFailureRow>> byOrder = new HashMap<>();
        for (BlockedFailureRow failure : failures) {
            byOrder.computeIfAbsent(failure.orderId(), id -> new ArrayList<>()).add(failure);
        }
        return byOrder;
    }

    // 마이그레이션이 넣어 둔 한 줄. 없으면 마이그레이션이 빠진 것
    private PayoutBlockedOrderRefresh getRefresh() {
        return payoutBlockedOrderRefreshRepository.findById(PayoutBlockedOrderRefresh.SINGLE_ROW_ID)
                .orElseThrow(() -> new IllegalStateException("막힌 주문 표 갱신 상태 줄이 없습니다 (마이그레이션 확인)."));
    }

    // 주문 하나의 판정 결과. status가 null이면 막힌 주문이 아님(표에 있으면 지움)
    record JudgedOrder(Long orderId, PayoutBlockedStatus status, int failedRunCount, LocalDateTime lastFailedAt,
                       List<PayoutBlockedOrderCause> causes) {

        static JudgedOrder unblocked(Long orderId) {
            return new JudgedOrder(orderId, null, 0, null, List.of());
        }

        boolean blocked() {
            return status != null;
        }
    }

    // 같은 원인 기록을 가리는 키 (orderItemId는 배송비면 null이라 record의 equals가 null을 같게 봄)
    private record CauseKey(Long orderItemId, PayoutEventType eventType, PayoutFailureReason reason, String detail) {
    }
}
