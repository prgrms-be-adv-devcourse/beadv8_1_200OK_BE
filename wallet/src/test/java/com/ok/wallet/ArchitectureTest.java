package com.ok.wallet;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import com.ok.common.exception.ErrorCode;
import com.ok.wallet.WalletSchema;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.persistence.Entity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

@AnalyzeClasses(packages = {"com.ok.wallet", "com.ok.common.event"}, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final DescribedPredicate<JavaClass> ApplicationEventPublisher에_의존하는 = DescribedPredicate.describe(
            "use ApplicationEventPublisher",
            javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().isAssignableTo(ApplicationEventPublisher.class)));

    // 레이어

    @ArchTest
    static final ArchRule 헥사고날_레이어는_한_방향으로만_의존한다 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("com.ok.wallet.domain..")
            .layer("Application").definedBy("com.ok.wallet.application..")
            .layer("AdapterIn").definedBy("com.ok.wallet.adapter.in..")
            .layer("AdapterOut").definedBy("com.ok.wallet.adapter.out..")
            .whereLayer("AdapterIn").mayNotBeAccessedByAnyLayer()
            .whereLayer("AdapterOut").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("AdapterIn", "AdapterOut")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "AdapterOut")
            .because("진입점(in)과 외부 연결(out)이 서비스를 거쳐서만 만나야 연동 방식을 바꿔도 비즈니스 코드가 바뀌지 않는다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 패키지_간_순환_의존이_없다 = slices()
            .matching("com.ok.wallet.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다. "
                    + "모듈 간 순환 금지(컨벤션 2.2)를 모듈 안 패키지까지 넓혀 적용한다");

    @ArchTest
    static final ArchRule 입력_어댑터는_유스케이스_포트와_dto만_안다 = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ok.wallet.application.service..", "com.ok.wallet.application.port.out..",
                    "com.ok.wallet.adapter.out..", "com.ok.wallet.domain..")
            .because("컨트롤러와 리스너는 외부 입력의 진입점이다. 트랜잭션과 도메인 처리는 유스케이스 구현에 맡기고, 구현이 바뀌어도 진입점이 바뀌지 않게 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 입력_포트는_도메인을_모른다 = noClasses()
            .that().resideInAPackage("..application.port.in..")
            .should().dependOnClassesThat().resideInAPackage("com.ok.wallet.domain..")
            .because("입력 포트는 진입점에 공개하는 약속이다. 엔티티를 돌려주면 진입점이 도메인을 알게 되고, 트랜잭션 밖에서 바꾼 값은 오류 없이 저장되지 않는다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule application은_영속_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class)
                    .or(resideInAnyPackage("org.springframework.data.jpa..", "jakarta.persistence..", "com.querydsl..")))
            .because("서비스는 저장소 포트에만 의존해야 영속 기술을 바꿔도 비즈니스 코드가 바뀌지 않는다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule application은_HTTP_클라이언트에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.client..", "org.springframework.http..")
            .because("토스 호출은 출력 포트 뒤에 두어, 서비스가 HTTP 요청·응답 모양을 모르고 승인 결과만 보게 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 포트는_인터페이스다 = classes()
            .that().resideInAnyPackage("..application.port.in..", "..application.port.out..")
            .should().beInterfaces()
            .because("포트는 구현을 숨기는 경계라 인터페이스여야 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 출력_포트는_Spring_Data_Repository를_상속하지_않는다 = noClasses()
            .that().resideInAPackage("..application.port.out..")
            .should().beAssignableTo(org.springframework.data.repository.Repository.class)
            .because("포트가 Repository를 상속하면 Spring Data가 포트 자체로 저장소를 하나 더 만들어 빈이 둘이 된다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 출력_포트의_구현은_adapter_out에_있다 = classes()
            .that().areAssignableTo(resideInAPackage("..application.port.out.."))
            .and().resideOutsideOfPackage("..application.port.out..")
            .should().resideInAPackage("..adapter.out..")
            .because("외부 시스템과 닿는 구현은 adapter.out에 모아 바뀌는 범위가 경로로 드러나게 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    // 포트는 JPA를 몰라 @Lock을 붙일 수 없다. *JpaRepository에서 다시 선언하지 않으면 Spring Data가 이름만 보고
    // 잠금 없는 조회를 만들어 오류 없이 돈다. 잠금 조회는 이름(ForUpdate, ForShare)으로 찾고, 이름과 잠금 종류가 맞는지도 본다.
    // ForUpdate에 읽기 잠금이 붙으면 두 트랜잭션이 함께 읽고 함께 바꾸려다 교착이 나므로 잠금이 없는 것과 같은 실수다
    @ArchTest
    static final ArchRule 잠금_조회는_JPA_저장소에_Lock과_함께_다시_선언한다 = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("..application.port.out..")
            .and().haveNameMatching(".*For(Update|Share).*")
            .should(new ArchCondition<JavaMethod>("be redeclared in a *JpaRepository with a @Lock mode matching its name") {
                @Override
                public void check(JavaMethod portMethod, ConditionEvents events) {
                    LockModeType expected = portMethod.getName().contains("ForUpdate")
                            ? LockModeType.PESSIMISTIC_WRITE
                            : LockModeType.PESSIMISTIC_READ;
                    List<JavaClass> jpaRepositories = portMethod.getOwner().getAllSubclasses().stream()
                            .filter(sub -> resideInAPackage("..adapter.out.persistence..").test(sub)
                                    && sub.getSimpleName().endsWith("JpaRepository"))
                            .toList();
                    // 구현체마다 따로 확인해, 여럿 중 하나만 잠금을 가져도 통과하는 일을 막는다.
                    // 구현체가 없으면 allMatch가 참이 되므로 따로 실패시킨다
                    boolean ok = !jpaRepositories.isEmpty() && jpaRepositories.stream()
                            .allMatch(repository -> repository.getMethods().stream()
                                    .anyMatch(method -> method.getName().equals(portMethod.getName())
                                            && method.getRawParameterTypes().equals(portMethod.getRawParameterTypes())
                                            && method.tryGetAnnotationOfType(Lock.class)
                                                    .map(lock -> lock.value() == expected)
                                                    .orElse(false)));
                    events.add(new SimpleConditionEvent(portMethod, ok,
                            portMethod.getFullName() + (ok ? " is" : " is not")
                                    + " redeclared in a *JpaRepository with @Lock(" + expected + ")"));
                }
            })
            .because("잠금이 빠진 조회는 동시에 요청이 몰릴 때만 이중 차감 같은 문제로 드러난다. 빌드에서 바로 실패하게 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule ApplicationEventPublisher는_adapter_out만_사용한다 = noClasses()
            .that().resideOutsideOfPackage("..adapter.out..")
            .should().dependOnClassesThat().areAssignableTo(ApplicationEventPublisher.class)
            .because("서비스는 출력 포트로만 발행하고 구현체(adapter.out)가 발행 방식을 맡는다. 발행 방식을 바꿔도 서비스가 바뀌지 않게 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 이벤트_클래스는_adapter만_사용한다 = classes()
            .that().resideInAPackage("com.ok.common.event..")
            // common.event 안에서 서로 참조하는 것(DomainEvent, payload)은 모듈 간 계약 타입 내부의 일이다
            .should().onlyBeAccessed().byAnyPackage("com.ok.wallet.adapter..", "com.ok.common.event..")
            .because("모듈 간 계약 타입이 비즈니스 코드에 섞이지 않게 한다. 리스너는 이벤트를 Command나 값으로 바꿔 유스케이스를 호출하고, "
                    + "발행 포트는 값을 받는다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 이벤트_발행_구현체는_출력_포트를_구현한다 = classes()
            .that(ApplicationEventPublisher에_의존하는)
            // adapter.out 밖에서 쓰는 경우는 ApplicationEventPublisher는_adapter_out만_사용한다 규칙이 막는다
            .and().resideInAPackage("..adapter.out..")
            .should().implement(resideInAPackage("..application.port.out.."))
            .because("서비스는 출력 포트만 알고, adapter.out의 구현체가 발행 방식을 맡는다. "
                    + "발행 방식(스프링 이벤트, 메시지 브로커)을 바꿀 때 구현체만 바꾸게 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule wallet은_common_외_다른_모듈에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("com.ok.wallet..")
            .should().dependOnClassesThat(resideInAPackage("com.ok..")
                    .and(not(resideInAnyPackage("com.ok.wallet..", "com.ok.common.."))))
            .because("모듈 경계를 지켜야 모듈을 독립적으로 이해하고 바꿀 수 있고, 모듈별 서버로 나눌 수 있다. 연동은 이벤트로 한다 (컨벤션 2.2)");

    // 이름과 위치

    @ArchTest
    static final ArchRule RestController는_Controller로_끝나고_adapter_in_web_패키지에_있다 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .andShould().resideInAPackage("..adapter.in.web..")
            .because("이름과 위치만 보고 컨트롤러를 찾을 수 있어야 한다 (컨벤션 3.2, 헥사고날 구조)");

    @ArchTest
    static final ArchRule Service는_Service로_끝나고_application_service_패키지에_있다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should().haveSimpleNameEndingWith("Service")
            .andShould().resideInAPackage("..application.service..")
            .because("이름과 위치만 보고 서비스를 찾을 수 있어야 한다 (컨벤션 3.2, 헥사고날 구조)");

    @ArchTest
    static final ArchRule 리포지토리는_adapter_out_persistence_패키지에_있다 = classes()
            .that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
            .should().resideInAPackage("..adapter.out.persistence..")
            .because("영속성 구현은 adapter.out.persistence에 모아 역할별로 찾기 쉽게 한다 (헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 엔티티는_domain_패키지에_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("com.ok.wallet.domain..")
            .because("엔티티가 domain 패키지에 있어야 레이어 규칙이 엔티티 의존을 검사할 수 있다 (헥사고날 구조, docs/DEVELOPMENT.md)");

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
            .andShould().resideOutsideOfPackage("..spec..")
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
            .should(new ArchCondition<JavaClass>("be annotated with @Table(catalog = WalletSchema.NAME)") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    boolean ok = javaClass.tryGetAnnotationOfType(Table.class)
                            .map(table -> WalletSchema.NAME.equals(table.catalog()))
                            .orElse(false);
                    events.add(new SimpleConditionEvent(javaClass, ok,
                            javaClass.getName() + (ok ? " has" : " does not have") + " @Table(catalog = WalletSchema.NAME)"));
                }
            })
            .because("모듈은 자기 MySQL 스키마(WalletSchema.NAME)에 테이블을 둔다. 엔티티마다 catalog로 지정해야 한다");
}
