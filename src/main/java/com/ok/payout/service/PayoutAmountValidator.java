package com.ok.payout.service;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// validateStep 금액 검사 판정 (#195). DB 조회·저장은 PayoutService.validate가 하고, 여기는 받은 값으로 판정만 함
// verifyStep의 PayoutDraftValidator와 역할이 다름:
//   여기(입력 검증) = 들어온 금액이 맞는지 후보 단위로 판정 → 틀린 주문만 실패로 빼고 나머지는 계속 정산
//   PayoutDraftValidator(결과 검증) = 우리 코드가 만든 draft가 맞는지 → 틀리면 Job 전체를 멈춤
//
// 판정 단위는 주문: 이번 스냅샷에 들어온 같은 주문의 후보를 묶어 먼저 판정한 뒤 실패 목록을 한 번에 돌려줌
//   주문상품마다 바로 실패를 만들면, 한 주문에 틀린 주문상품이 2개일 때 같은 후보가 AMOUNT_MISMATCH와 ORDER_LOCKED로 두 번 기록됨
//   → 실패 기록 UK(snapshot_id, candidate_id) 위반 → validate 롤백 → 같은 데이터라 매 배치 같은 이유로 실패
// 아직 구매확정되지 않은 같은 주문의 상품은 기다리지 않음: 들어온 상품만 판정하고, 나머지는 들어오는 배치에서 판정
//
// 검사 순서 (앞 검사에 걸린 주문은 뒤 검사를 하지 않음 → 후보당 실패 기록 1건)
//   0) 차감 기록 없음(DEDUCTION_MISSING): 이 클래스 밖에서 먼저 걸러짐 (차감 기록이 있는 주문의 후보만 여기로 옴)
//   1) 초과 청구: 그 주문 후보 합계 > 지갑 차감액이면 이번 스냅샷의 그 주문 후보 전부 AMOUNT_MISMATCH
//   2) 수수료: 주문상품의 원래 수수료 ≠ 수수료 계산이면 그 주문상품 후보는 AMOUNT_MISMATCH, 같은 주문 나머지는 ORDER_LOCKED
final class PayoutAmountValidator {

    // 초과 청구 실패 상세. 어느 주문상품이 틀렸는지 알 수 없다는 한계를 함께 남김 (관리자가 상세만 보고 원인을 한 상품으로 오해하지 않게)
    private static final String OVERCHARGE_DETAIL = "주문 합계 초과: 차감액 %d, 후보 합계 %d (어느 주문상품이 틀렸는지 판별 불가)";
    // 수수료 불일치 실패 상세 (기대값 = 수수료 계산, 실제값 = 수수료 후보, 계산에 쓴 판매 금액)
    private static final String FEE_MISMATCH_DETAIL = "수수료 불일치: 기대값 %d, 실제값 %d (판매 금액 %d 기준)";
    // 함께 보류된 후보의 상세: 원인 주문상품을 남겨, 이 후보 자체에는 문제가 없다는 것을 바로 알 수 있게 함
    private static final String ORDER_LOCKED_DETAIL = "같은 주문의 수수료 불일치로 보류 (원인 주문상품 %s)";

    // 판정에 쓰는 값 묶음 (모두 PayoutQueryRepository 조회 결과)
    record Input(
            List<PayoutCandidateItem> targets,                  // 검사 대상: 이번 스냅샷 후보 중 차감 기록이 있고 아직 실패가 아닌 것
            Map<Long, Long> candidateTotalsByOrder,             // 주문 ID → 그 주문 정산후보 전체 합계 (스냅샷 밖 포함)
            Map<Long, Long> deductionsByOrder,                  // 주문 ID → 지갑 차감액
            List<PayoutCandidateItem> originalSaleCandidates    // 대상 주문상품의 원래(조정 아닌) 판매 대금·수수료 후보 (스냅샷 밖 포함)
    ) {
    }

    private PayoutAmountValidator() {
    }

    // 금액 검사에 걸린 후보의 실패 기록 목록을 반환 (비어 있으면 모두 통과). 후보 하나에 실패는 최대 1건
    static List<PayoutFailure> validate(Long snapshotId, Input input) {
        Map<Long, SalePair> pairsByOrderItem = pairByOrderItem(input.originalSaleCandidates());

        List<PayoutFailure> failures = new ArrayList<>();
        for (Map.Entry<Long, List<PayoutCandidateItem>> order : groupByOrder(input.targets()).entrySet()) {
            Long orderId = order.getKey();
            List<PayoutCandidateItem> orderTargets = order.getValue();

            // 1) 초과 청구 (주문 단위): 정산후보 합계(판매자 몫 + 수수료)가 구매자에게서 받은 돈(차감액)보다 많으면 안 됨
            //    작은 것은 허용: 차감액(주문 결제 금액)에는 있지만 정산후보로 오지 않는 금액이 있을 수 있음
            // 한계: 주문 합계로만 비교해서 어느 주문상품이 틀렸는지 모름
            //   먼저 들어온 틀린 금액은 합계가 아직 작아 통과·지급되고, 나중에 들어온 맞는 주문상품이 합계를 넘겨 잡힐 수 있음
            // 합계에는 아직 대기 기간(15일)이 안 지난 같은 주문의 후보도 들어감 (이번 배치 대상이 아니어도 DB에 이미 있는 후보)
            //   예) 결제 30,000 = 상품1 10,000(이번 배치) + 상품2 20,000(대기 중)인데 상품2가 25,000으로 들어옴
            //       → 합계 35,000 > 30,000이라 이번 배치에서 주문이 잠기고, 문제없는 상품1도 함께 지급되지 않음
            //   이렇게 둔 이유: 틀린 금액이 지급되기 전에 더 일찍 잡으려고. 대신 정상 상품이 틀린 상품과 함께 묶임
            // TODO(논의 필요): 한 주문의 상품들을 함께 잡아 확인하는 지금 방식을 유지할지, 정산이 주문상품별 비교 금액을 모두 갖춰
            //   틀린 상품만 골라내는 방향(아래 TODO(#52))으로 갈지 등 처리 방향을 정해야 함
            // TODO(#52): 주문 생성 시점의 주문상품별 금액을 이벤트로 받아 주문상품 단위로 비교 (없으면 ORDER_MISSING)
            // TODO(논의 필요): 합계에 조정 후보도 넣음. 조정 방향(±)이 정해지면 다시 볼 것 (+조정은 정상인데 초과로, -조정은 초과를 가릴 수 있음)
            // TODO(#52): 부분 환불이 생기면 기준값을 "차감액 − 환불액"으로 바꿔야 함 (주문 #28 구매확정 금액에 환불 수량 미반영)
            // TODO(#52): 할인 도입 예정. 할인이 생기면 이 검사 기준도 함께 다시 정해야 함 (지금은 정해진 것 없음)
            // 조회 조건상 대상 주문에는 항상 합계·차감액이 있음 (대상 = 스냅샷 후보 중 차감 기록이 있는 것)
            long deducted = input.deductionsByOrder().get(orderId);
            long total = input.candidateTotalsByOrder().get(orderId);
            if (total > deducted) {
                String detail = String.format(OVERCHARGE_DETAIL, deducted, total);
                for (PayoutCandidateItem candidate : orderTargets) {
                    failures.add(PayoutFailure.of(snapshotId, candidate, PayoutFailureReason.AMOUNT_MISMATCH, detail));
                }
                continue;
            }

            // 2) 수수료 (주문상품 단위로 판정, 실패 기록은 주문 단위로 한 번에)
            Map<Long, String> feeMismatches = findFeeMismatches(orderTargets, pairsByOrderItem);
            if (feeMismatches.isEmpty()) {
                continue;
            }
            String lockedDetail = fitDetail(String.format(ORDER_LOCKED_DETAIL, feeMismatches.keySet()));
            for (PayoutCandidateItem candidate : orderTargets) {
                String mismatchDetail = candidate.getOrderItemId() == null ? null : feeMismatches.get(candidate.getOrderItemId());
                if (mismatchDetail != null) {
                    failures.add(PayoutFailure.of(snapshotId, candidate, PayoutFailureReason.AMOUNT_MISMATCH, mismatchDetail));
                } else {
                    failures.add(PayoutFailure.of(snapshotId, candidate, PayoutFailureReason.ORDER_LOCKED, lockedDetail));
                }
            }
        }
        return failures;
    }

    // 주문 하나의 대상 후보 중 수수료가 틀린 주문상품을 찾아 (주문상품 ID → 실패 상세)로 반환
    // 이번 스냅샷에 들어온 주문상품만 봄 (같은 주문의 아직 안 들어온 상품은 들어오는 배치에서 판정)
    //
    // 수수료 계산식: 원래 수수료 = 수수료 계산(원래 판매 대금 + 원래 수수료)
    //   정산후보 생성 때 판매 금액에서 수수료를 떼어 대금을 만들었으므로(PayoutCandidateService), 둘을 더하면 판매 금액이 됨
    //   같은 식으로 다시 계산하는 것이라 정상 경로에서는 항상 일치함. 형식상 검사로 두고, 후보가 다른 경로로 잘못 들어온 경우를 잡음
    // TODO(논의 필요): 수수료 정책(수수료율 변경, 0원 수수료 이벤트 등)이 바뀌면 이 검사도 함께 바꿔야 함
    //   지금은 현재 수수료율 하나로 계산해서, 예전 수수료율로 만든 후보나 수수료 면제 이벤트로 0원이 된 후보(판매 금액이 있는데 수수료 0원)를 틀렸다고 봄
    //   (소액이라 버림으로 0원이 된 수수료는 계산과 같아 통과함)
    // TODO(논의 필요): 조정 후보는 수수료 검사에서 뺌 (원래 후보끼리만 비교). 조정은 환불 작업 때 다른 방향으로 처리 예정
    private static Map<Long, String> findFeeMismatches(List<PayoutCandidateItem> orderTargets,
                                                       Map<Long, SalePair> pairsByOrderItem) {
        Map<Long, String> mismatches = new LinkedHashMap<>();
        for (PayoutCandidateItem candidate : orderTargets) {
            Long orderItemId = candidate.getOrderItemId();
            // 배송비 후보(주문상품 없음)는 수수료 검사 대상이 아님. 같은 주문상품의 후보가 여럿이면 한 번만 판정
            if (orderItemId == null || mismatches.containsKey(orderItemId)) {
                continue;
            }
            SalePair pair = pairsByOrderItem.get(orderItemId);
            // 원래 대금·수수료 짝이 다 있어야 계산할 수 있음. 짝이 없으면(대금만 있음 등) 수수료 검사를 건너뜀 (초과 청구 검사는 이미 함)
            if (pair == null || pair.amount() == null || pair.fee() == null) {
                continue;
            }
            long saleAmount = pair.amount() + pair.fee();
            long expectedFee = PayoutPolicy.calculateSaleFee(saleAmount);
            if (pair.fee() != expectedFee) {
                mismatches.put(orderItemId, String.format(FEE_MISMATCH_DETAIL, expectedFee, pair.fee(), saleAmount));
            }
        }
        return mismatches;
    }

    // 상세가 DB 길이(255자)를 넘으면 뒤를 자름
    // 원인 주문상품이 아주 많으면 넘을 수 있는데, 그대로 저장하면 예외 → validate 롤백 → 같은 데이터라 매 배치 실패하므로 잘라서라도 기록함
    private static String fitDetail(String detail) {
        if (detail.length() <= PayoutFailure.MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, PayoutFailure.MAX_DETAIL_LENGTH - 3) + "...";
    }

    // 주문상품 하나의 원래 판매 대금·수수료 금액 (없는 쪽은 null)
    private record SalePair(Long amount, Long fee) {
    }

    // 원래 판매 대금·수수료 후보를 주문상품별 짝으로 묶음
    private static Map<Long, SalePair> pairByOrderItem(List<PayoutCandidateItem> originals) {
        Map<Long, SalePair> pairs = new LinkedHashMap<>();
        for (PayoutCandidateItem candidate : originals) {
            SalePair pair = pairs.getOrDefault(candidate.getOrderItemId(), new SalePair(null, null));
            if (candidate.getEventType() == PayoutEventType.SALE_AMOUNT) {
                pair = new SalePair(candidate.getAmount(), pair.fee());
            } else if (candidate.getEventType() == PayoutEventType.SALE_FEE) {
                pair = new SalePair(pair.amount(), candidate.getAmount());
            }
            pairs.put(candidate.getOrderItemId(), pair);
        }
        return pairs;
    }

    // 검사 대상 후보를 주문별로 묶음 (조회 순서(id)를 유지해 실패 기록 순서가 매번 같게 함)
    private static Map<Long, List<PayoutCandidateItem>> groupByOrder(List<PayoutCandidateItem> targets) {
        Map<Long, List<PayoutCandidateItem>> byOrder = new LinkedHashMap<>();
        for (PayoutCandidateItem candidate : targets) {
            if (!byOrder.containsKey(candidate.getOrderId())) {
                byOrder.put(candidate.getOrderId(), new ArrayList<>());
            }
            byOrder.get(candidate.getOrderId()).add(candidate);
        }
        return byOrder;
    }
}
