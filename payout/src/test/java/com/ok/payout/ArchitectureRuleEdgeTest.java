package com.ok.payout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;

import com.ok.payout.adapter.in.batch.EdgeFakeBatchEntityUser;
import com.ok.payout.adapter.in.batch.EdgeFakeBatchRunType;
import com.ok.payout.adapter.in.event.EdgeFakeEventBatchUser;
import com.ok.payout.adapter.in.shared.EdgeFakeBridge;
import com.ok.payout.adapter.in.web.EdgeFakeWebRunType;
import com.ok.payout.adapter.in.web.EdgeFakeWebScheduler;
import com.ok.payout.adapter.out.EdgeFakeRelayUser;
import com.ok.payout.adapter.out.shared.EdgeFakeOutShared;
import com.ok.payout.adapter.shared.EdgeFakeSharedAdapter;
import com.ok.payout.application.port.in.EdgeFakeInPortResult;
import com.ok.payout.application.port.shared.EdgeFakePortShared;
import com.ok.payout.application.support.EdgeFakeSupportHelper;
import com.ok.payout.domain.type.EdgeFakeTypeClass;
import com.ok.payout.domain.type.EdgeFakeTypeEnumUser;
import com.ok.payout.domain.type.PayoutRunType;
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
    void 포트와_out_어댑터_아래_새_하위_패키지를_만들면_허용_목록_규칙이_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakePortShared.class, EdgeFakeOutShared.class);

        // when
        EvaluationResult sut = ArchitectureTest.정산_클래스는_정해진_패키지에만_있다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakePortShared.class, EdgeFakeOutShared.class);
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
        EvaluationResult sut = ArchitectureTest.배치를_뺀_입구는_배치_중_PayoutJobRunner만_안다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeWebScheduler.class);
    }

    @Test
    void 다른_입구가_배치_내부_클래스를_쓰면_실패한다() {
        // given: web → event → batch 우회 경로의 가운데 다리
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeEventBatchUser.class);

        // when
        EvaluationResult sut = ArchitectureTest.배치를_뺀_입구는_배치_중_PayoutJobRunner만_안다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeEventBatchUser.class);
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

    @Test
    void domain_type에_enum이_아닌_클래스나_다른_패키지를_쓰는_enum을_두면_실패한다() {
        // given: 규칙이 빈 입력으로 조용히 통과하지 않도록 정상 enum(PayoutRunType)도 함께 넣는다
        JavaClasses classes = new ClassFileImporter().importClasses(
                PayoutRunType.class, EdgeFakeTypeClass.class, EdgeFakeTypeEnumUser.class);

        // when
        EvaluationResult sut = ArchitectureTest.domain_type에는_enum만_있고_정산의_다른_패키지를_모른다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeTypeClass.class, EdgeFakeTypeEnumUser.class);
        assertThat(String.join("\n", sut.getFailureReport().getDetails())).doesNotContain(PayoutRunType.class.getName());
    }

    @Test
    void 정상_enum만_있으면_domain_type_규칙은_통과한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(PayoutRunType.class);

        // when
        EvaluationResult sut = ArchitectureTest.domain_type에는_enum만_있고_정산의_다른_패키지를_모른다.evaluate(classes);

        // then
        assertThat(sut.hasViolation()).isFalse();
    }

    @Test
    void 웹이_domain_type을_쓰면_입구_규칙이_실패한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeWebRunType.class);

        // when
        EvaluationResult sut = ArchitectureTest.배치를_뺀_입구는_입력_포트만_안다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeWebRunType.class);
    }

    @Test
    void 배치가_domain_type을_쓰는_것은_배치_규칙이_허용한다() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeBatchRunType.class);

        // when
        EvaluationResult sut = ArchitectureTest.배치_어댑터는_정책_상수와_domain_type_외_domain과_서비스_출력_포트를_모른다.evaluate(classes);

        // then: 규칙은 대상이 비면 조용히 통과하므로(failOnEmptyShould=false), 가짜 클래스가 실제로 검사 대상이었는지도 확인한다
        assertThat(classes.contain(EdgeFakeBatchRunType.class)).isTrue();
        assertThat(sut.hasViolation()).isFalse();
    }

    @Test
    void 배치가_도메인_엔티티를_쓰면_배치_규칙이_실패한다() {
        // given: 금지 쪽을 확인한다. 위 허용 예(domain.type)와 짝이다
        JavaClasses classes = new ClassFileImporter().importClasses(EdgeFakeBatchEntityUser.class);

        // when
        EvaluationResult sut = ArchitectureTest.배치_어댑터는_정책_상수와_domain_type_외_domain과_서비스_출력_포트를_모른다.evaluate(classes);

        // then
        assertViolationMentions(sut, EdgeFakeBatchEntityUser.class);
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
