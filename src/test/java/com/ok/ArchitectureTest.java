package com.ok;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import com.ok.common.event.DomainEvent;
import com.ok.common.exception.ErrorCode;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.persistence.Entity;

@AnalyzeClasses(packages = "com.ok", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final DescribedPredicate<JavaClass> ApplicationEventPublisher에_의존하는 = DescribedPredicate.describe(
            "use ApplicationEventPublisher",
            javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().isAssignableTo(ApplicationEventPublisher.class)));

    // 레이어

    // freeze: 기존 위반은 archunit_store에 기준선으로 두고, 새 위반만 실패시킨다.
    @ArchTest
    static final ArchRule 컨트롤러_서비스_리포지토리_도메인_순서로만_의존한다 = freeze(layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Domain").definedBy("..domain..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Service", "Repository")
            .because("레이어가 한 방향으로만 의존해야 한 레이어의 변경이 다른 레이어로 번지지 않는다. 엔티티는 서비스 밖으로 내보내지 않는다 (컨벤션 2.1, 2.3)"));

    @ArchTest
    static final ArchRule 패키지_간_순환_의존이_없다 = slices()
            .matching("com.ok.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다. "
                    + "모듈 간 순환 금지(컨벤션 2.2)를 모듈 안 패키지까지 넓혀 적용한다");

    @ArchTest
    static final ArchRule 이벤트_리스너는_controller_repository_domain을_쓰지_않는다 = noClasses()
            .that().resideInAPackage("..event..")
            // common.event는 모듈 간 이벤트 DTO라 리스너가 아니다
            .and().resideOutsideOfPackage("com.ok.common.event..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..repository..", "..domain..")
            .because("리스너는 컨트롤러처럼 외부 입력의 진입점이다. 트랜잭션과 도메인 처리는 서비스에 맡긴다 (컨벤션 2.1)");

    // freeze
    @ArchTest
    static final ArchRule ApplicationEventPublisher는_event_패키지만_사용한다 = freeze(noClasses()
            .that().resideOutsideOfPackage("..event..")
            .should().dependOnClassesThat().areAssignableTo(ApplicationEventPublisher.class)
            .because("서비스는 service/publisher 인터페이스로만 발행하고 구현체(event)가 발행 방식을 맡는다. 발행 방식을 바꿔도 서비스가 바뀌지 않게 한다 (컨벤션 2.1)"));

    // freeze
    @ArchTest
    static final ArchRule 이벤트_클래스는_event_패키지만_사용한다 = freeze(classes()
            .that().resideInAPackage("com.ok.common.event..")
            .should().onlyBeAccessed().byAnyPackage("..event..")
            .because("모듈 간 계약 타입이 비즈니스 코드에 섞이지 않게 한다. 리스너는 이벤트를 Command나 값으로 바꿔 서비스를 호출하고, "
                    + "발행 인터페이스는 값을 받는다 (컨벤션 2.1)"));

    @ArchTest
    static final ArchRule 서비스는_event_패키지에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..service..")
            // common.event(이벤트 record)는 이벤트_클래스는_event_패키지만_사용한다 규칙이 검사한다
            .should().dependOnClassesThat(resideInAPackage("..event..")
                    .and(not(resideInAPackage("com.ok.common.event.."))))
            .because("서비스는 service의 발행 인터페이스에만 의존하고, 구현체(event)를 바꿔도 서비스가 바뀌지 않게 한다 (컨벤션 2.1)");

    @ArchTest
    static final ArchRule 이벤트_발행_구현체는_service의_발행_인터페이스를_구현한다 = classes()
            .that(ApplicationEventPublisher에_의존하는)
            // event 밖에서 쓰는 경우는 ApplicationEventPublisher는_event_패키지만_사용한다 규칙이 막는다
            .and().resideInAPackage("..event..")
            .should().implement(resideInAPackage("..service.."))
            .because("서비스는 service의 발행 인터페이스만 알고, event의 구현체가 발행 방식을 맡는다. "
                    + "발행 방식(스프링 이벤트, 메시지 브로커)을 바꿀 때 구현체만 바꾸게 한다 (컨벤션 2.1)");

    // freeze
    @ArchTest
    static final ArchRule 모듈_간_이벤트는_DomainEvent를_구현한다 = freeze(classes()
            .that().resideInAPackage("com.ok.common.event..")
            // 이벤트가 담는 값 레코드(판매자별 묶음, 배송지 등)는 payload 하위 패키지에 두고 검사에서 뺀다. 따로 발행하지 않는다
            .and().resideOutsideOfPackage("com.ok.common.event.payload..")
            .and().areNotInterfaces()
            .should().implement(DomainEvent.class)
            .because("발행 구현체가 모듈 간 계약으로 정한 이벤트(DomainEvent)만 발행하게 한다 (컨벤션 2.1)"));

    // 이름과 위치

    @ArchTest
    static final ArchRule RestController는_Controller로_끝나고_controller_패키지에_있다 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .andShould().resideInAPackage("..controller..")
            .because("이름과 위치만 보고 컨트롤러를 찾을 수 있어야 한다 (컨벤션 3.2)");

    @ArchTest
    static final ArchRule Service는_Service로_끝나고_service_패키지에_있다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should().haveSimpleNameEndingWith("Service")
            .andShould().resideInAPackage("..service..")
            .because("이름과 위치만 보고 서비스를 찾을 수 있어야 한다 (컨벤션 3.2)");

    @ArchTest
    static final ArchRule 리포지토리는_repository_패키지에_있다 = classes()
            .that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
            .should().resideInAPackage("..repository..")
            .because("영속성 코드는 repository 패키지에 모아 역할별로 찾기 쉽게 한다 (컨벤션 2.1)");

    @ArchTest
    static final ArchRule 엔티티는_domain_패키지에_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain..")
            .because("엔티티가 domain 패키지에 있어야 레이어 규칙이 엔티티 의존을 검사할 수 있다 (컨벤션 2.1)");

    // freeze
    @ArchTest
    static final ArchRule ErrorCode_구현체는_ErrorCode로_끝나고_exception_패키지에_있다 = freeze(classes()
            .that().implement(ErrorCode.class)
            .should().haveSimpleNameEndingWith("ErrorCode")
            .andShould().resideInAPackage("..exception..")
            .because("모듈의 에러 코드를 exception 패키지 한곳에서 찾을 수 있어야 한다 (컨벤션 2.1, 3.2)"));

    // freeze
    @ArchTest
    static final ArchRule Swagger_인터페이스는_Spec으로_끝나고_컨트롤러와_같은_패키지에_있다 = freeze(classes()
            .that().areInterfaces()
            .and().areAnnotatedWith(Tag.class)
            .should().haveSimpleNameEndingWith("Spec")
            .andShould().notBePublic()
            .andShould().resideOutsideOfPackage("..spec..")
            .because("문서 어노테이션은 컨트롤러 옆에 두고, 다른 곳에서 쓰지 않으므로 공개하지 않는다 (컨벤션 3.3)"));

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
}
