package com.ok.payout.service;

import com.ok.payout.repository.PayoutQueryRepository.CountAndAmount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 검증 규칙만 숫자로 확인하는 단위 테스트 (DB·Spring 없음)
// 검증 규칙을 추가하면 여기에 통과·실패 케이스를 함께 추가
class PayoutDraftValidatorTest {

    @Test
    void 스냅샷과_draft의_건수_금액이_같고_판매자_헤더_합계가_맞으면_실패_사유가_없다() {
        // given: 스냅샷 4건 30,400원 = 판매자 3건 30,100원 + 시스템 1건 300원, 헤더 합계 30,100원
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                new CountAndAmount(4, 30_400), new CountAndAmount(3, 30_100), new CountAndAmount(1, 300), 30_100,
                new CountAndAmount(0, 0), 0);

        // when
        List<String> sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void draft_건수가_스냅샷보다_적으면_실패_사유가_1건_생긴다() {
        // given: 스냅샷 4건인데 draft는 3건 (금액은 같아도 건수가 다름)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                new CountAndAmount(4, 30_400), new CountAndAmount(2, 30_100), new CountAndAmount(1, 300), 30_100,
                new CountAndAmount(0, 0), 0);

        // when
        List<String> sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut).containsExactly("스냅샷=CountAndAmount[count=4, amount=30400], draft+실패=CountAndAmount[count=3, amount=30400]");
    }

    @Test
    void draft_금액이_스냅샷과_다르면_실패_사유가_1건_생긴다() {
        // given: 건수는 같은데 금액이 다름 (금액이 다르게 옮겨진 항목)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                new CountAndAmount(4, 30_400), new CountAndAmount(3, 30_000), new CountAndAmount(1, 300), 30_000,
                new CountAndAmount(0, 0), 0);

        // when
        List<String> sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut).containsExactly("스냅샷=CountAndAmount[count=4, amount=30400], draft+실패=CountAndAmount[count=4, amount=30300]");
    }

    @Test
    void 판매자_헤더_합계가_항목_합계와_다르면_실패_사유가_1건_생긴다() {
        // given: 항목은 스냅샷과 맞는데 판매자 헤더 합계만 다름 (헤더 합계 계산 버그)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                new CountAndAmount(4, 30_400), new CountAndAmount(3, 30_100), new CountAndAmount(1, 300), 29_000,
                new CountAndAmount(0, 0), 0);

        // when
        List<String> sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut).containsExactly("판매자 헤더 금액=29000, 판매자 항목 금액=30100");
    }

    @Test
    void 여러_검사가_동시에_실패하면_사유를_모두_모은다() {
        // given: 건수도 다르고 헤더 합계도 다름
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                new CountAndAmount(4, 30_400), new CountAndAmount(2, 30_100), new CountAndAmount(1, 300), 29_000,
                new CountAndAmount(0, 0), 0);

        // when
        List<String> sut = PayoutDraftValidator.validate(input);

        // then: 첫 실패에서 멈추지 않음
        assertThat(sut).hasSize(2);
    }

    @Test
    void 스냅샷이_draft와_실패_기록의_합과_같으면_실패_사유가_없다() {
        // given: 스냅샷 5건 43,400원 = 판매자 3건 30,100원 + 시스템 1건 300원 + 실패 1건 13,000원
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                new CountAndAmount(5, 43_400), new CountAndAmount(3, 30_100), new CountAndAmount(1, 300), 30_100,
                new CountAndAmount(1, 13_000), 0);

        // when
        List<String> sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 실패로_기록된_후보가_draft에_들어가면_실패_사유가_1건_생긴다() {
        // given: 건수·금액 합은 맞지만, 실패 기록 1건이 draft에도 들어감 (draft 제외 조건 버그)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(
                new CountAndAmount(5, 43_400), new CountAndAmount(3, 30_100), new CountAndAmount(1, 300), 30_100,
                new CountAndAmount(1, 13_000), 1);

        // when
        List<String> sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut).containsExactly("실패로 기록됐는데 draft에 들어간 후보 수=1");
    }
}
