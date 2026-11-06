package com.ok.refund;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import com.ok.common.exception.ErrorCode;
import com.ok.refund.RefundSchema;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * 환불 모듈의 헥사고날 구조(이슈 #481)를 검사한다.
 * <ul>
 *   <li>{@code domain}: 환불 도메인 모델. JPA·Spring 같은 기술을 모른다 (저장 모양은 adapter.out.persistence의 JpaEntity가 맡는다).</li>
 *   <li>{@code application}: {@code port.in}(유스케이스와 입출력 값), {@code port.out}(저장소·외부 호출 포트), {@code service}(유스케이스 구현).</li>
 *   <li>{@code adapter.in}: {@code web}(컨트롤러).</li>
 *   <li>{@code adapter.out}: {@code persistence}(JPA 구현), {@code order}(주문 모듈 호출).</li>
 * </ul>
 */
@AnalyzeClasses(packages = {"com.ok.refund", "com.ok.common.event"}, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // 발행 도구를 직접 쓰는 클래스 = 발행 구현체로 본다. 아래 이벤트_발행_도구는_adapter_out_event만_사용한다와 같은 두 도구 기준이다
    private static final DescribedPredicate<JavaClass> 발행_도구에_의존하는 = DescribedPredicate.describe(
            "use ApplicationEventPublisher or KafkaOperations",
            javaClass -> {
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (target.isAssignableTo(ApplicationEventPublisher.class) || target.isAssignableTo(KafkaOperations.class)) {
                        return true;
                    }
                }
                return false;
            });

    // 레이어

    // 입구(adapter.in)가 domain을 쓰는지는 아래 별도 규칙(입력 어댑터는 입력 포트만 안다)이 막는다. 그래서 여기서 Domain 접근 허용에 AdapterIn을 뺀다
    @ArchTest
    static final ArchRule 헥사고날_레이어는_한_방향으로만_의존한다 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("com.ok.refund.domain..")
            .layer("Application").definedBy("com.ok.refund.application..")
            .layer("AdapterIn").definedBy("com.ok.refund.adapter.in..")
            .layer("AdapterOut").definedBy("com.ok.refund.adapter.out..")
            .whereLayer("AdapterIn").mayNotBeAccessedByAnyLayer()
            .whereLayer("AdapterOut").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("AdapterIn", "AdapterOut")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "AdapterOut")
            .because("진입점(in)과 외부 연결(out)이 서비스를 거쳐서만 만나야 연동 방식을 바꿔도 비즈니스 코드가 바뀌지 않는다 (환불 헥사고날 구조, 이슈 #481)");

    // 레이어 규칙은 레이어에 든 패키지끼리만 본다. 레이어 밖 새 패키지(예: support)를 거치면 서비스 -> adapter.out 같은 의존이 검사를 피하므로
    // 환불 클래스가 놓일 수 있는 패키지를 정해 두고, 레이어 밖 패키지(루트·config·exception)는 바깥 레이어를 모르게 한다
    @ArchTest
    static final ArchRule 환불_클래스는_정해진_패키지에만_있다 = classes()
            .that().resideInAPackage("com.ok.refund..")
            .should().resideInAnyPackage("com.ok.refund", "com.ok.refund.domain..", "com.ok.refund.application.port.in..",
                    "com.ok.refund.application.port.out..", "com.ok.refund.application.service..",
                    "com.ok.refund.adapter.in..", "com.ok.refund.adapter.out..", "com.ok.refund.config..",
                    "com.ok.refund.exception..")
            .because("레이어 밖에 새 패키지를 만들면 레이어 규칙을 우회할 수 있다. 새 역할이 필요하면 이 목록과 레이어 규칙을 함께 고친다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 레이어_밖_패키지는_application과_adapter를_모른다 = noClasses()
            .that().resideInAnyPackage("com.ok.refund", "com.ok.refund.config..", "com.ok.refund.exception..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ok.refund.application..", "com.ok.refund.adapter..")
            .because("루트·config·exception은 레이어 규칙이 보지 않으므로, 여기서 application·adapter를 쓰면 서비스가 이 패키지를 거쳐 어댑터에 닿을 수 있다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 패키지_간_순환_의존이_없다 = slices()
            .matching("com.ok.refund.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다. "
                    + "모듈 간 순환 금지(컨벤션 2.2)를 모듈 안 패키지까지 넓혀 적용한다");

    // web을 나열하지 않고 adapter.in 전체에 건다. 새 입구(예: adapter.in.event)가 생겨도 같은 규칙을 받게 한다
    @ArchTest
    static final ArchRule 입력_어댑터는_입력_포트만_안다 = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ok.refund.application.service..", "com.ok.refund.application.port.out..",
                    "com.ok.refund.adapter.out..", "com.ok.refund.domain..")
            .because("컨트롤러는 외부 입력의 진입점이다. 트랜잭션과 도메인 처리는 유스케이스 구현에 맡기고, 구현이 바뀌어도 진입점이 바뀌지 않게 한다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 출력_어댑터는_입력_포트와_서비스를_모른다 = noClasses()
            .that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ok.refund.application.service..", "com.ok.refund.application.port.in..", "com.ok.refund.adapter.in..")
            .because("출력 어댑터는 출력 포트를 구현하는 쪽이다. 유스케이스나 입구를 거꾸로 부르면 호출이 순환한다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule application은_영속_메시징_HTTP_클라이언트_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class)
                    .or(resideInAnyPackage("org.springframework.data.jpa..", "jakarta.persistence..", "org.hibernate..",
                            "com.querydsl..", "org.springframework.jdbc..", "org.springframework.orm..",
                            "org.springframework.kafka..", "org.apache.kafka..",
                            "org.springframework.web.client..", "org.springframework.web.reactive..",
                            "org.springframework.http.client..", "java.net.http..", "org.apache.hc..", "okhttp3..", "feign..")))
            .because("서비스는 포트에만 의존해야 영속, 메시징, 외부 호출 기술을 바꿔도 비즈니스 코드가 바뀌지 않는다. "
                    + "Page, Pageable(org.springframework.data.domain)은 조회 결과 형식이라 허용한다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule domain은_application_adapter와_프레임워크_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(resideInAnyPackage("..application..", "..adapter..", "jakarta.persistence..",
                    "org.hibernate..", "org.springframework..", "org.apache.kafka..", "jakarta.servlet.."))
            .because("도메인은 바깥 레이어와 저장소, 메시징, 웹 기술을 몰라야 단독으로 이해하고 테스트할 수 있다. "
                    + "common 예외와 환불 예외, lombok은 허용한다 (환불 헥사고날 구조, 이슈 #481)");

    // 위 금지 목록에 없는 새 기술이나 다른 모듈 타입이 도메인에 들어와도 잡도록 허용 목록으로 한 번 더 건다
    @ArchTest
    static final ArchRule domain은_허용된_패키지에만_의존한다 = classes()
            .that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat(resideInAnyPackage("com.ok.refund.domain..", "com.ok.refund.exception..",
                    "com.ok.common.exception..", "java..", "lombok.."))
            .because("허용 목록 밖 타입이 도메인에 섞이면 도메인이 다른 기술이나 모듈에 묶인다 (환불 헥사고날 구조, 이슈 #481)");

    // 포트

    // 포트가 주고받는 값(record, enum)은 그 포트 옆에 둔다: 입력 포트의 Command·Result는 port.in, 외부 조회 값은 port.out
    @ArchTest
    static final ArchRule 포트는_인터페이스다 = classes()
            .that().resideInAnyPackage("..application.port.in..", "..application.port.out..")
            .and().areNotRecords()
            .and().areNotEnums()
            .should().beInterfaces()
            .because("포트는 구현을 숨기는 경계라 인터페이스여야 한다. 포트가 주고받는 값(record, enum)만 함께 둔다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 출력_포트는_Spring_Data_Repository를_상속하지_않는다 = noClasses()
            .that().resideInAPackage("..application.port.out..")
            .should().beAssignableTo(org.springframework.data.repository.Repository.class)
            .because("포트가 Repository를 상속하면 Spring Data가 포트 자체로 저장소를 하나 더 만들어 빈이 둘이 된다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 출력_포트의_구현은_adapter_out에_있다 = classes()
            .that().areAssignableTo(resideInAPackage("..application.port.out.."))
            .and().resideOutsideOfPackage("..application.port.out..")
            .should().resideInAPackage("..adapter.out..")
            .because("외부 시스템과 닿는 구현은 adapter.out에 모아 바뀌는 범위가 경로로 드러나게 한다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule JpaRepository는_adapter_out_persistence_패키지에_있다 = classes()
            .that().haveSimpleNameEndingWith("JpaRepository")
            .should().resideInAPackage("..adapter.out.persistence..")
            .because("JPA 저장소는 adapter.out.persistence에 모아 영속 구현을 한곳에서 찾게 한다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 리포지토리는_adapter_out_persistence_패키지에_있다 = classes()
            .that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
            .should().resideInAPackage("..adapter.out.persistence..")
            .because("영속성 구현은 adapter.out.persistence에 모아 역할별로 찾기 쉽게 한다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 엔티티는_adapter_out_persistence_패키지에_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..adapter.out.persistence..")
            .because("도메인을 JPA에서 분리해(#481) JPA 엔티티는 영속 어댑터에만 둔다. 도메인 패키지에 엔티티가 다시 생기지 않게 한다");

    // 이벤트

    // KafkaTemplate 대신 상위 인터페이스(KafkaOperations)로 받아도 걸리도록 KafkaOperations 기준으로 검사한다
    @ArchTest
    static final ArchRule 이벤트_발행_도구는_adapter_out_event만_사용한다 = noClasses()
            .that().resideOutsideOfPackage("..adapter.out.event..")
            .should().dependOnClassesThat().areAssignableTo(ApplicationEventPublisher.class)
            .orShould().dependOnClassesThat().areAssignableTo(KafkaOperations.class)
            .because("서비스는 출력 포트로만 발행하고 구현체(adapter.out.event)가 발행 방식을 맡는다. 발행 방식을 바꿔도 서비스가 바뀌지 않게 한다 (환불 헥사고날 구조, 이슈 #481)");

    // 지금은 adapter.out.event가 없어 검사 대상이 비지만, archunit.properties의 failOnEmptyShould=false라 실패하지 않는다.
    // 발행이 생기는 날(예: 지갑 Kafka) 구현체가 포트 없이 서비스에 바로 주입되는 일을 처음부터 막는다
    @ArchTest
    static final ArchRule 이벤트_발행_구현체는_출력_포트를_구현한다 = classes()
            .that(발행_도구에_의존하는)
            // adapter.out.event 밖에서 쓰는 경우는 이벤트_발행_도구는_adapter_out_event만_사용한다 규칙이 막는다
            .and().resideInAPackage("..adapter.out.event..")
            .should().implement(resideInAPackage("..application.port.out.."))
            .because("서비스는 출력 포트만 알고, adapter.out.event의 구현체가 발행 방식을 맡는다. "
                    + "발행 방식(스프링 이벤트, 메시지 브로커)을 바꿀 때 구현체만 바꾸게 한다 (환불 헥사고날 구조, 이슈 #481)");

    @ArchTest
    static final ArchRule 이벤트_클래스는_이벤트_어댑터만_사용한다 = noClasses()
            .that().resideOutsideOfPackages("..adapter.in.event..", "..adapter.out.event..", "com.ok.common.event..")
            .should().dependOnClassesThat().resideInAPackage("com.ok.common.event..")
            .because("모듈 간 계약 타입이 비즈니스 코드에 섞이지 않게 한다. 리스너는 이벤트를 Command나 값으로 바꿔 유스케이스를 호출하고, "
                    + "발행 포트는 값을 받는다 (환불 헥사고날 구조, 이슈 #481)");

    // 이름과 위치

    @ArchTest
    static final ArchRule RestController는_Controller로_끝나고_adapter_in_web_패키지에_있다 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .andShould().resideInAPackage("..adapter.in.web..")
            .because("이름과 위치만 보고 컨트롤러를 찾을 수 있어야 한다 (컨벤션 3.2, 환불 헥사고날 구조)");

    @ArchTest
    static final ArchRule Service는_Service로_끝나고_application_service_패키지에_있다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should().haveSimpleNameEndingWith("Service")
            .andShould().resideInAPackage("..application.service..")
            .because("이름과 위치만 보고 서비스를 찾을 수 있어야 한다 (컨벤션 3.2, 환불 헥사고날 구조)");

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
            .should(new ArchCondition<JavaClass>("be annotated with @Table(catalog = RefundSchema.NAME)") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    boolean ok = javaClass.tryGetAnnotationOfType(Table.class)
                            .map(table -> RefundSchema.NAME.equals(table.catalog()))
                            .orElse(false);
                    events.add(new SimpleConditionEvent(javaClass, ok,
                            javaClass.getName() + (ok ? " has" : " does not have") + " @Table(catalog = RefundSchema.NAME)"));
                }
            })
            .because("모듈은 자기 MySQL 스키마(RefundSchema.NAME)에 테이블을 둔다. 엔티티마다 catalog로 지정해야 한다");
}
