package com.ok.payout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;

import com.ok.payout.adapter.in.shared.EdgeFakeBridge;
import com.ok.payout.adapter.in.web.EdgeFakeWebScheduler;
import com.ok.payout.adapter.out.EdgeFakeRelayUser;
import com.ok.payout.adapter.shared.EdgeFakeSharedAdapter;
import com.ok.payout.application.port.in.EdgeFakeInPortResult;
import com.ok.payout.application.support.EdgeFakeSupportHelper;
import com.ok.relay.EdgeFakeRelayPublisher;

/**
 * 구조 규칙(ArchitectureTest)이 위반을 실제로 잡는지, 위반 클래스를 직접 넣어 확인한다.
 * 규칙을 고친 뒤에는 허용한 항목마다 같은 위반을 넣어 봐야 빈틈이 보인다(#469).
 * 나중에 누가 규칙을 고쳐 빈틈을 다시 열면 여기서 실패한다.
 * 가짜 위반 클래스(EdgeFake*)는 테스트 소스에만 있고 애노테이션이 없어 Spring 빈이 되지 않는다.
 * 실패 보고서에 가짜 클래스 이름이 있는지도 보므로, 규칙이 엉뚱한 이유로 실패해 통과하는 일을 막는다.
 */
class ArchitectureRuleEdgeTest {

    @Test
    void 새_하위_패키지를_만들면_허용_목록_규칙이_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeSharedAdapter.class, EdgeFakeSupportHelper.class);

        // when
        EvaluationResult sut = ArchitectureTest.정산_클래스는_정해진_패키지에만_있다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeSharedAdapter.class, EdgeFakeSupportHelper.class);
    }

    @Test
    void 입구_아래_새_하위_패키지를_만들면_허용_목록_규칙이_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeBridge.class);

        // when
        EvaluationResult sut = ArchitectureTest.정산_클래스는_정해진_패키지에만_있다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeBridge.class);
    }

    @Test
    void 정산_밖_패키지를_쓰면_다른_모듈_규칙이_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeRelayUser.class, EdgeFakeRelayPublisher.class);

        // when
        EvaluationResult sut = ArchitectureTest.정산_코드는_com_ok_중_payout과_common만_안다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeRelayUser.class);
    }

    @Test
    void 정산_밖_클래스가_출력_포트를_구현하면_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeRelayPublisher.class);

        // when
        EvaluationResult sut = ArchitectureTest.정산_모듈_클래스는_com_ok_payout에_있다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeRelayPublisher.class);
    }

    @Test
    void 웹이_Runner가_아닌_배치_클래스를_쓰면_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeWebScheduler.class);

        // when
        EvaluationResult sut = ArchitectureTest.웹은_배치_중_PayoutJobRunner만_안다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeWebScheduler.class);
    }

    @Test
    void 입력_포트가_출력_포트를_쓰면_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeInPortResult.class);

        // when
        EvaluationResult sut = ArchitectureTest.입력_포트는_출력_포트를_모른다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeInPortResult.class);
    }

    // 위반이 있고, 그 보고서에 가짜 클래스 이름이 있어야 한다 (다른 이유로 실패한 것과 구분)
    private static void assertViolationMentions(EvaluationResult result, Class<?>... fakes) {
        assertThat(result.hasViolation()).isTrue();
        String details = String.join("\n", result.getFailureReport().getDetails());
        for (Class<?> fake : fakes) {
            assertThat(details).contains(fake.getName());
        }
    }
}
