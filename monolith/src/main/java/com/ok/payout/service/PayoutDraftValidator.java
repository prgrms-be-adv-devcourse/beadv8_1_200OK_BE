package com.ok.payout.service;

import com.ok.payout.repository.PayoutQueryRepository.CountAndAmount;

import java.util.ArrayList;
import java.util.List;

// draft 검증 규칙 모음 (verifyStep의 비교 판단만 담당, DB 조회·예외 처리는 PayoutService.verify가 함)
// 검사를 추가할 때:
//   1) 필요한 숫자가 새로 있으면 Input에 필드 추가 + PayoutQueryRepository에 집계 쿼리 추가
//   2) check...() 메서드를 하나 만들고 validate()에 한 줄 추가
// 실패 사유는 모두 모아서 돌려줌 (첫 실패에서 멈추지 않아, 여러 문제를 한 번에 확인 가능)
final class PayoutDraftValidator {

    // 검증에 쓰는 숫자 묶음 (모두 DB 집계 결과)
    record Input(
            CountAndAmount snapshotItems,   // 스냅샷 목록 = 원래 처리해야 할 양
            CountAndAmount sellerItems,     // 판매자 몫 draft 항목
            CountAndAmount systemItems,     // 시스템 몫 draft 항목
            long sellerHeaderAmount,        // 판매자 draft 헤더(판매자별 합계)의 금액 합
            CountAndAmount failures,        // 실패 기록 = 처리하지 않고 뺀 양
            long draftedFailureCount        // draft에도 들어가고 실패로도 기록된 후보 수 (정상이면 0)
    ) {
    }

    private PayoutDraftValidator() {
    }

    // 모든 검사를 실행하고 실패 사유 목록을 반환 (비어 있으면 통과)
    static List<String> validate(Input input) {
        List<String> failures = new ArrayList<>();
        checkDraftItemsMatchSnapshot(input, failures);
        checkSellerHeaderMatchesItems(input, failures);
        checkNoFailureInDraft(input, failures);
        return failures;
    }

    // 검사 1: 스냅샷 목록 = draft 항목(판매자 + 시스템) + 실패 기록, 건수·금액 모두
    //   예) 스냅샷 4건 43,400원 = 판매자 2건 30,100원 + 시스템 1건 300원 + 실패 1건 13,000원 → 통과
    //   잡는 것: draft에서도 빠지고 실패 기록도 없는 후보, 금액이 다르게 옮겨진 항목
    private static void checkDraftItemsMatchSnapshot(Input input, List<String> failures) {
        CountAndAmount handled = input.sellerItems().plus(input.systemItems()).plus(input.failures());
        if (!input.snapshotItems().equals(handled)) {
            failures.add("스냅샷=" + input.snapshotItems() + ", draft+실패=" + handled);
        }
    }

    // 검사 2: 판매자 헤더 금액 합계 = 판매자 항목 금액 합계
    //   예) 헤더 A 9,700 + B 19,400 + C 1,000 = 30,100 = 판매자 항목 합계 30,100 → 통과
    //   잡는 것: draft 헤더 합계 계산 버그 (draft는 검증 실패 시에도 남는 기록이라 스스로 맞아야 함)
    //   참고: commitStep은 헤더 금액을 쓰지 않고 항목을 다시 더해 payout 금액을 만들므로, 헤더가 틀려도 장부 금액에는 번지지 않음
    //         (이 검사와 Payout의 재계산이 이중으로 막음, Payout.addItem 주석 참고)
    private static void checkSellerHeaderMatchesItems(Input input, List<String> failures) {
        long sellerItemAmount = input.sellerItems().amount();
        if (input.sellerHeaderAmount() != sellerItemAmount) {
            failures.add("판매자 헤더 금액=" + input.sellerHeaderAmount() + ", 판매자 항목 금액=" + sellerItemAmount);
        }
    }

    // 검사 3: 실패로 기록된 후보는 draft에 없어야 함
    //   잡는 것: 실패한 후보가 정산에 섞임 (draft 제외 조건 ④ 버그). 검사 1은 건수가 우연히 맞으면 못 잡을 수 있어 따로 확인
    private static void checkNoFailureInDraft(Input input, List<String> failures) {
        if (input.draftedFailureCount() != 0) {
            failures.add("실패로 기록됐는데 draft에 들어간 후보 수=" + input.draftedFailureCount());
        }
    }
}
