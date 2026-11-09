package com.ok.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.net.URISyntaxException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;

import com.ok.PayoutApplication;
import com.ok.common.exception.ErrorCode;
import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.domain.PayoutPolicy;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 정산 모듈의 헥사고날 구조(이슈 #441)를 검사한다.
 * <ul>
 *   <li>{@code domain}: 엔티티와 정책, {@code domain.type}(배치 입구와 엔티티가 같이 쓰는 enum). 바깥 레이어와 저장소·메시징·배치·웹 기술을 모른다 (엔티티라 JPA·Hibernate 애노테이션은 쓴다).</li>
 *   <li>{@code application}: {@code port.in}(유스케이스와 그 입출력 값 Command·Query·Result), {@code port.out}(저장소·발행 포트와 그 값 Row·발행 값), {@code service}(유스케이스 구현).</li>
 *   <li>{@code adapter.in}: {@code web}(컨트롤러), {@code event}(Spring·Kafka 리스너), {@code batch}(잡 설정·실행기·스케줄러).</li>
 *   <li>{@code adapter.out}: {@code persistence}(JPA·QueryDSL 구현), {@code event}(Kafka 발행 구현).</li>
 * </ul>
 */
@AnalyzeClasses(packages = {"com.ok.payout", "com.ok.common.event"}, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // 레이어

    // 배치 어댑터(PayoutJobConfig, PayoutJobRunner)는 domain.PayoutPolicy 상수와 domain.type enum을 쓰므로 AdapterIn -> Domain을 허용한다.
    // 컨트롤러 -> PayoutJobRunner는 같은 AdapterIn 안이라 이 규칙이 보지 않는다 (승인된 설계).
    // 배치가 아닌 입구(web·event)가 배치 중 무엇을 쓸 수 있는지는 아래 `배치를_뺀_입구는_배치_중_PayoutJobRunner만_안다`가 좁힌다.
    // 입구가 domain을 어디까지 쓸 수 있는지는 아래 별도 규칙이 좁힌다 (web·event는 금지, batch는 PayoutPolicy와 domain.type만)
    @ArchTest
    static final ArchRule 헥사고날_레이어는_한_방향으로만_의존한다 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("com.ok.payout.domain..")
            .layer("Application").definedBy("com.ok.payout.application..")
            .layer("AdapterIn").definedBy("com.ok.payout.adapter.in..")
            .layer("AdapterOut").definedBy("com.ok.payout.adapter.out..")
            .whereLayer("AdapterIn").mayNotBeAccessedByAnyLayer()
            .whereLayer("AdapterOut").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("AdapterIn", "AdapterOut")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "AdapterIn", "AdapterOut")
            .because("진입점(in)과 외부 연결(out)이 서비스를 거쳐서만 만나야 연동 방식을 바꿔도 비즈니스 코드가 바뀌지 않는다 (정산 헥사고날 구조, 이슈 #441)");

    // 레이어 규칙은 레이어에 든 패키지끼리만 본다. 레이어 밖 새 패키지(예: support)를 거치면 서비스 → adapter.out 같은 의존이 검사를 피하므로
    // 정산 클래스가 놓일 수 있는 패키지를 정해 두고, 레이어 밖 패키지(루트·config·exception)는 바깥 레이어를 모르게 한다
    // adapter..·application..처럼 넓게 열면 adapter.support 같은 새 하위 패키지가 검사를 피하므로 실제 쓰는 하위 패키지만 나열한다 (이슈 #469)
    @ArchTest
    static final ArchRule 정산_클래스는_정해진_패키지에만_있다 = classes()
            .that().resideInAPackage("com.ok.payout..")
            .should().resideInAnyPackage("com.ok.payout", "com.ok.payout.domain..",
                    "com.ok.payout.application.port.in..", "com.ok.payout.application.port.out..",
                    "com.ok.payout.application.service..",
                    "com.ok.payout.adapter.in.web..", "com.ok.payout.adapter.in.event..", "com.ok.payout.adapter.in.batch..",
                    "com.ok.payout.adapter.out.persistence..", "com.ok.payout.adapter.out.event..",
                    "com.ok.payout.config..", "com.ok.payout.exception..")
            .because("레이어 밖이나 정해지지 않은 하위 패키지를 만들면 레이어 규칙을 우회할 수 있다(예: web → adapter.in.shared → batch는 웹-배치 규칙이 직접 의존만 봐서 통과한다). "
                    + "새 입구나 out 어댑터 패키지가 필요하면 이 목록과 관련 규칙을 함께 고친다 (정산 헥사고날 구조, 이슈 #469)");

    // 다른 모듈의 내부 타입을 알면 모듈을 따로 떼어낼 수 없다(MSA 분리 대비). com.ok 아래에서는 자기 모듈과 common만 허용한다
    @ArchTest
    static final ArchRule 정산_코드는_com_ok_중_payout과_common만_안다 = noClasses()
            .that().resideInAPackage("com.ok.payout..")
            .should().dependOnClassesThat(resideInAPackage("com.ok..")
                    .and(not(resideInAnyPackage("com.ok.payout..", "com.ok.common.."))))
            .because("모듈 간 연결은 common의 계약 타입으로만 해야 다른 모듈이 바뀌어도 정산이 바뀌지 않는다 (정산 헥사고날 구조, 이슈 #469)");

    @ArchTest
    static final ArchRule 레이어_밖_패키지는_application과_adapter를_모른다 = noClasses()
            .that().resideInAnyPackage("com.ok.payout", "com.ok.payout.config..", "com.ok.payout.exception..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ok.payout.application..", "com.ok.payout.adapter..")
            .because("루트·config·exception은 레이어 규칙이 보지 않으므로, 여기서 application·adapter를 쓰면 서비스가 이 패키지를 거쳐 어댑터에 닿을 수 있다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule 패키지_간_순환_의존이_없다 = slices()
            .matching("com.ok.payout.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다. "
                    + "모듈 간 순환 금지(컨벤션 2.2)를 모듈 안 패키지까지 넓혀 적용한다");

    // web·event를 나열하지 않고 adapter.in 전체에서 batch만 뺀다. 새 입구(예: adapter.in.grpc)는 이 규칙을 자동으로 받는다.
    // 다만 새 하위 패키지는 `정산_클래스는_정해진_패키지에만_있다`의 허용 목록에도 함께 추가해야 한다
    @ArchTest
    static final ArchRule 배치를_뺀_입구는_입력_포트만_안다 = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .and().resideOutsideOfPackage("..adapter.in.batch..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ok.payout.application.service..", "com.ok.payout.application.port.out..",
                    "com.ok.payout.adapter.out..", "com.ok.payout.domain..")
            .because("컨트롤러와 리스너는 외부 입력의 진입점이다. 트랜잭션과 도메인 처리는 유스케이스 구현에 맡기고, 구현이 바뀌어도 진입점이 바뀌지 않게 한다 (정산 헥사고날 구조, 이슈 #441)");

    // 배치는 domain 중 정산 정책 상수(PayoutPolicy)와 domain.type의 enum(PayoutRunType)만 쓸 수 있다: 한 번에 처리할 판매자 수(PayoutJobConfig), 대기 일수(PayoutJobRunner), 실행 유형(#485)
    // 엔티티 등 나머지 domain 타입은 웹·이벤트 입구처럼 입력 포트를 거쳐야 한다. 웹·이벤트 입구는 domain.type도 쓰지 못한다(위 규칙, api.md)
    @ArchTest
    static final ArchRule 배치_어댑터는_정책_상수와_domain_type_외_domain과_서비스_출력_포트를_모른다 = noClasses()
            .that().resideInAPackage("..adapter.in.batch..")
            .should().dependOnClassesThat(
                    resideInAnyPackage("com.ok.payout.application.service..", "com.ok.payout.application.port.out..",
                            "com.ok.payout.adapter.out..")
                            .or(resideInAPackage("com.ok.payout.domain..")
                                    .and(not(equivalentTo(PayoutPolicy.class)))
                                    .and(not(resideInAPackage("com.ok.payout.domain.type..")))))
            .because("배치 시작기와 Tasklet도 입력 포트로만 비즈니스를 호출해야 구현이 바뀌어도 배치 코드가 바뀌지 않는다. "
                    + "배치 크기·대기 일수 같은 정책 상수와, 실행 유형 같은 enum(domain.type)만 직접 쓴다 (정산 헥사고날 구조, 이슈 #441, #485)");

    // domain.type은 배치 입구가 domain 중 유일하게 쓸 수 있는 칸이다. 여기에 일반 클래스나 다른 패키지에 기대는 코드가 들어오면
    // 배치가 엔티티·서비스에 닿는 통로가 되므로, enum만 두고 정산의 다른 패키지·JPA·Spring을 모르게 한다 (#469의 "목록 밖 패키지로 우회" 교훈)
    @ArchTest
    static final ArchRule domain_type에는_enum만_있고_정산의_다른_패키지를_모른다 = classes()
            .that().resideInAPackage("com.ok.payout.domain.type..")
            .should().beEnums()
            .andShould().onlyDependOnClassesThat(not(
                    resideInAPackage("com.ok.payout..").and(not(resideInAPackage("com.ok.payout.domain.type..")))
                            .or(resideInAnyPackage("jakarta.persistence..", "org.hibernate..", "org.springframework.."))))
            .because("domain.type은 배치 입구가 쓸 수 있는 유일한 domain 하위 칸이다. enum 외 클래스나 다른 패키지·JPA·Spring 의존이 들어오면 "
                    + "입구 규칙을 우회하는 통로가 된다 (정산 헥사고날 구조, 이슈 #469, #485)");

    // 배치를 뺀 입구는 배치의 시작 버튼(PayoutJobRunner)만 누른다. 잡 설정·Tasklet 같은 배치 내부는 알지 못하게 한다 (ADR 441 "관리자 실행 API").
    // 웹만 대상으로 하면 web → event → batch처럼 다른 입구가 다리가 되어 우회하므로 batch를 뺀 모든 입구를 대상으로 한다 (이슈 #469)
    @ArchTest
    static final ArchRule 배치를_뺀_입구는_배치_중_PayoutJobRunner만_안다 = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .and().resideOutsideOfPackage("..adapter.in.batch..")
            .should().dependOnClassesThat(resideInAPackage("..adapter.in.batch..").and(not(equivalentTo(PayoutJobRunner.class))))
            .because("배치가 아닌 입구는 배치 시작기만 알아야 배치 내부 구성이 바뀌어도 입구가 바뀌지 않고, 다른 입구가 다리가 되어 배치 내부에 닿지도 못한다 (정산 헥사고날 구조, 이슈 #469)");

    @ArchTest
    static final ArchRule application은_영속_메시징_배치_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class)
                    .or(resideInAnyPackage("org.springframework.data.jpa..", "jakarta.persistence..", "com.querydsl..",
                            "org.springframework.jdbc..", "org.springframework.kafka..", "org.apache.kafka..",
                            "org.springframework.batch..")))
            .because("서비스는 포트에만 의존해야 영속, 메시징, 배치 기술을 바꿔도 비즈니스 코드가 바뀌지 않는다. "
                    + "Page, Pageable(org.springframework.data.domain)은 조회 결과 형식이라 허용한다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule domain은_application_adapter와_프레임워크_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class)
                    .or(resideInAnyPackage("..application..", "..adapter..", "org.springframework.jdbc..",
                            "org.springframework.kafka..", "org.apache.kafka..", "org.springframework.batch..",
                            "org.springframework.web..")))
            .because("도메인은 바깥 레이어와 저장소, 메시징, 배치, 웹 기술을 몰라야 단독으로 이해하고 테스트할 수 있다. "
                    + "JPA, Hibernate 애노테이션과 common, 정산 예외는 허용한다 (정산 헥사고날 구조, 이슈 #441)");

    // 포트

    // 입구가 보는 port.in은 조회 Row 모양이 바뀌어도 변하지 않아야 한다. 변환은 서비스의 PayoutQueryResultMapper가 맡는다 (delivery 모듈과 같은 방식)
    @ArchTest
    static final ArchRule 입력_포트는_출력_포트를_모른다 = noClasses()
            .that().resideInAPackage("..application.port.in..")
            .should().dependOnClassesThat().resideInAPackage("..application.port.out..")
            .because("입력 포트가 출력 포트의 값을 쓰면 저장소 조회 모양이 바뀔 때 입구까지 바뀐다. 변환은 서비스에서 한다 (정산 헥사고날 구조, 이슈 #469)");

    // 포트가 주고받는 값(record)은 그 포트 옆에 둔다: 입력 포트의 Command·Query·Result는 port.in, 조회 결과 Row와 발행 값은 port.out. 한곳(dto)에 모으면 dto ↔ port.out 순환이 생긴다
    @ArchTest
    static final ArchRule 포트는_인터페이스다 = classes()
            .that().resideInAnyPackage("..application.port.in..", "..application.port.out..")
            .and().areNotRecords()
            .should().beInterfaces()
            .because("포트는 구현을 숨기는 경계라 인터페이스여야 한다. 포트가 주고받는 값(record)만 함께 둔다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule 출력_포트는_Spring_Data_Repository를_상속하지_않는다 = noClasses()
            .that().resideInAPackage("..application.port.out..")
            .should().beAssignableTo(org.springframework.data.repository.Repository.class)
            .because("포트가 Repository를 상속하면 Spring Data가 포트 자체로 저장소를 하나 더 만들어 빈이 둘이 된다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule 출력_포트의_구현은_adapter_out에_있다 = classes()
            .that().areAssignableTo(resideInAPackage("..application.port.out.."))
            .and().resideOutsideOfPackage("..application.port.out..")
            .should().resideInAPackage("..adapter.out..")
            .because("외부 시스템과 닿는 구현은 adapter.out에 모아 바뀌는 범위가 경로로 드러나게 한다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule JpaRepository는_adapter_out_persistence_패키지에_있다 = classes()
            .that().haveSimpleNameEndingWith("JpaRepository")
            .should().resideInAPackage("..adapter.out.persistence..")
            .because("JPA 저장소 어댑터는 adapter.out.persistence에 모아 영속 구현을 한곳에서 찾게 한다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule 리포지토리는_adapter_out_persistence_패키지에_있다 = classes()
            .that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
            .should().resideInAPackage("..adapter.out.persistence..")
            .because("영속성 구현은 adapter.out.persistence에 모아 역할별로 찾기 쉽게 한다 (정산 헥사고날 구조, 이슈 #441)");

    // 이벤트

    // KafkaTemplate 대신 상위 인터페이스(KafkaOperations)로 받아도 걸리도록 KafkaOperations 기준으로 검사한다
    @ArchTest
    static final ArchRule 이벤트_발행_도구는_adapter_out_event만_사용한다 = noClasses()
            .that().resideOutsideOfPackage("..adapter.out.event..")
            .should().dependOnClassesThat().areAssignableTo(ApplicationEventPublisher.class)
            .orShould().dependOnClassesThat().areAssignableTo(KafkaOperations.class)
            .because("서비스는 출력 포트로만 발행하고 구현체(adapter.out.event)가 발행 방식을 맡는다. 발행 방식을 바꿔도 서비스가 바뀌지 않게 한다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule 발행_구현체는_출력_포트를_구현한다 = classes()
            .that().resideInAPackage("..adapter.out.event..")
            .and().haveSimpleNameEndingWith("Publisher")
            .should().implement(resideInAPackage("..application.port.out.."))
            .because("서비스는 출력 포트만 알고 발행 구현체가 그 포트를 구현해야 발행 방식(Spring 이벤트, Kafka, outbox)을 바꿔도 서비스가 바뀌지 않는다 (정산 헥사고날 구조, 이슈 #441)");

    // 메서드 호출뿐 아니라 필드·매개변수·상속으로 쓰는 것까지 잡도록 "의존" 기준으로 검사한다
    // 받는 쪽(adapter.in.event)과 보내는 쪽(adapter.out.event, 지갑 Spring 이벤트 발행)만 계약 타입을 쓴다. common.event 안의 상호 참조는 계약 타입 내부의 일이다
    // TODO(#343): 지갑이 Spring 이벤트 수신을 지우면 adapter.out.event 허용을 뺀다
    @ArchTest
    static final ArchRule 이벤트_클래스는_이벤트_어댑터만_사용한다 = noClasses()
            .that().resideOutsideOfPackages("..adapter.in.event..", "..adapter.out.event..", "com.ok.common.event..")
            .should().dependOnClassesThat().resideInAPackage("com.ok.common.event..")
            .because("모듈 간 계약 타입이 비즈니스 코드에 섞이지 않게 한다. 리스너는 이벤트를 Command나 값으로 바꿔 유스케이스를 호출하고, "
                    + "발행 포트는 값을 받는다 (정산 헥사고날 구조, 이슈 #441)");

    // 이름과 위치

    // @AnalyzeClasses(com.ok.payout, com.ok.common.event)는 정산 모듈 안이지만 다른 패키지(예: com.ok.relay)에 둔 클래스를 보지 못한다.
    // Spring은 그 클래스도 빈으로 묶어 정산 포트를 구현하게 할 수 있으므로, 모듈 출력 전체를 따로 가져와 아래 @Test에서 검사한다.
    // @ArchTest 필드로 두면 위의 가져오기 범위가 적용되어 의미가 없다
    static final ArchRule 정산_모듈_클래스는_com_ok_payout에_있다 = classes()
            .that().doNotHaveFullyQualifiedName(PayoutApplication.class.getName())
            .should().resideInAPackage("com.ok.payout..")
            .because("정산 모듈의 클래스가 com.ok.payout 밖에 있으면 패키지 기반 구조 규칙이 보지 못한다 (정산 헥사고날 구조, 이슈 #469)");

    @Test
    void 정산_모듈_클래스는_모두_com_ok_payout_아래에_있다() throws URISyntaxException {
        // given
        Path main = Path.of(PayoutApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        JavaClasses classes = new ClassFileImporter().importPath(main);
        // archunit.properties의 failOnEmptyShould=false 때문에 아무 클래스도 못 가져와도 규칙이 통과한다.
        // 출력 위치가 바뀌어 가져오기가 비어도 조용히 통과하지 않도록, 모듈의 대표 클래스가 들어 있는지 먼저 확인한다
        assertThat(classes.contain(PayoutApplication.class)).isTrue();
        assertThat(classes.contain("com.ok.payout.application.service.PayoutService")).isTrue();

        // when & then
        정산_모듈_클래스는_com_ok_payout에_있다.check(classes);
    }

    @ArchTest
    static final ArchRule RestController는_Controller로_끝나고_adapter_in_web_패키지에_있다 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .andShould().resideInAPackage("..adapter.in.web..")
            .because("이름과 위치만 보고 컨트롤러를 찾을 수 있어야 한다 (컨벤션 3.2, 정산 헥사고날 구조)");

    @ArchTest
    static final ArchRule Service는_Service로_끝나고_application_service_패키지에_있다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should().haveSimpleNameEndingWith("Service")
            .andShould().resideInAPackage("..application.service..")
            .because("이름과 위치만 보고 서비스를 찾을 수 있어야 한다 (컨벤션 3.2, 정산 헥사고날 구조)");

    @ArchTest
    static final ArchRule 엔티티는_domain_패키지에_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("com.ok.payout.domain..")
            .because("엔티티가 domain 패키지에 있어야 레이어 규칙이 엔티티 의존을 검사할 수 있다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule ErrorCode_구현체는_ErrorCode로_끝나고_exception_패키지에_있다 = classes()
            .that().implement(ErrorCode.class)
            .should().haveSimpleNameEndingWith("ErrorCode")
            .andShould().resideInAPackage("..exception..")
            .because("모듈의 에러 코드를 exception 패키지 한곳에서 찾을 수 있어야 한다 (컨벤션 2.1, 3.2)");

    @ArchTest
    static final ArchRule Swagger_인터페이스는_Spec으로_끝나고_컨트롤러와_같은_패키지에_있다 = classes()
            .that().areInterfaces()
            .and().areAnnotatedWith(Tag.class)
            .should().haveSimpleNameEndingWith("Spec")
            .andShould().notBePublic()
            .andShould().resideInAPackage("..adapter.in.web..")
            .because("문서 어노테이션은 컨트롤러 옆에 두고, 다른 곳에서 쓰지 않으므로 공개하지 않는다 (컨벤션 3.3)");

    // 기타

    @ArchTest
    static final ArchRule 컨트롤러_클래스에_Transactional을_붙이지_않는다 = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().beAnnotatedWith(Transactional.class)
            .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("트랜잭션은 서비스에서 열고 닫는다. 컨트롤러에서 열면 응답을 만드는 동안에도 DB 연결을 잡고 있다 (컨벤션 7.1)");

    @ArchTest
    static final ArchRule 컨트롤러_메서드에_Transactional을_붙이지_않는다 = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .or().areDeclaredInClassesThat().areAnnotatedWith(Controller.class)
            .should().beAnnotatedWith(Transactional.class)
            .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("트랜잭션은 서비스에서 열고 닫는다. 컨트롤러에서 열면 응답을 만드는 동안에도 DB 연결을 잡고 있다 (컨벤션 7.1)");

    @ArchTest
    static final ArchRule 표준_출력을_사용하지_않는다 = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .because("표준 출력은 로그 레벨과 형식을 따르지 않아 운영에서 추적하기 어렵다. 로거를 쓴다");

    // 모듈이 소유한 스키마에 테이블을 둔다. catalog를 빼먹으면 접속 기본 DB(기본 스키마)에서 테이블을 찾아 운영에서만 실패할 수 있다
    @ArchTest
    static final ArchRule 엔티티는_자기_모듈_스키마를_catalog로_지정한다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should(new ArchCondition<JavaClass>("be annotated with @Table(catalog = PayoutSchema.NAME)") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    boolean ok = javaClass.tryGetAnnotationOfType(Table.class)
                            .map(table -> PayoutSchema.NAME.equals(table.catalog()))
                            .orElse(false);
                    events.add(new SimpleConditionEvent(javaClass, ok,
                            javaClass.getName() + (ok ? " has" : " does not have") + " @Table(catalog = PayoutSchema.NAME)"));
                }
            })
            .because("모듈은 자기 MySQL 스키마(PayoutSchema.NAME)에 테이블을 둔다. 엔티티마다 catalog로 지정해야 한다");
}
