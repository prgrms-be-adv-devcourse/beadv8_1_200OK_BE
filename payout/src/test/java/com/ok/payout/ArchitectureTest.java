package com.ok.payout;

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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import com.ok.common.exception.ErrorCode;
import com.ok.payout.PayoutSchema;
import com.ok.payout.domain.PayoutPolicy;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * 정산 모듈의 헥사고날 구조(이슈 #441)를 검사한다.
 * <ul>
 *   <li>{@code domain}: 엔티티와 정책. 어느 기술에도 기대지 않는다.</li>
 *   <li>{@code application}: {@code port.in}(유스케이스), {@code port.out}(저장소·발행 포트), {@code service}(유스케이스 구현), {@code dto}.</li>
 *   <li>{@code adapter.in}: {@code web}(컨트롤러), {@code event}(Spring·Kafka 리스너), {@code batch}(잡 설정·실행기·스케줄러).</li>
 *   <li>{@code adapter.out}: {@code persistence}(JPA·QueryDSL 구현), {@code event}(Kafka 발행 구현).</li>
 * </ul>
 */
@AnalyzeClasses(packages = {"com.ok.payout", "com.ok.common.event"}, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // 레이어

    // 배치 어댑터(PayoutJobConfig, PayoutJobRunner)는 domain.PayoutPolicy 상수를 쓰므로 AdapterIn -> Domain을 허용한다.
    // 컨트롤러 -> PayoutJobRunner는 같은 AdapterIn 안이라 이 규칙이 보지 않는다 (승인된 설계).
    // 입구가 domain을 어디까지 쓸 수 있는지는 아래 별도 규칙이 좁힌다 (web·event는 금지, batch는 PayoutPolicy만)
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

    @ArchTest
    static final ArchRule 패키지_간_순환_의존이_없다 = slices()
            .matching("com.ok.payout.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다. "
                    + "모듈 간 순환 금지(컨벤션 2.2)를 모듈 안 패키지까지 넓혀 적용한다");

    @ArchTest
    static final ArchRule 컨트롤러와_리스너는_유스케이스_포트와_dto만_안다 = noClasses()
            .that().resideInAnyPackage("..adapter.in.web..", "..adapter.in.event..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ok.payout.application.service..", "com.ok.payout.application.port.out..",
                    "com.ok.payout.adapter.out..", "com.ok.payout.domain..")
            .because("컨트롤러와 리스너는 외부 입력의 진입점이다. 트랜잭션과 도메인 처리는 유스케이스 구현에 맡기고, 구현이 바뀌어도 진입점이 바뀌지 않게 한다 (정산 헥사고날 구조, 이슈 #441)");

    // 배치는 domain 중 정산 정책 상수(PayoutPolicy)만 쓸 수 있다: 한 번에 처리할 판매자 수(PayoutJobConfig), 대기 일수(PayoutJobRunner)
    // 엔티티 등 나머지 domain 타입은 웹·이벤트 입구처럼 입력 포트를 거쳐야 한다
    @ArchTest
    static final ArchRule 배치_어댑터는_정책_상수_외_domain과_서비스_출력_포트를_모른다 = noClasses()
            .that().resideInAPackage("..adapter.in.batch..")
            .should().dependOnClassesThat(
                    resideInAnyPackage("com.ok.payout.application.service..", "com.ok.payout.application.port.out..",
                            "com.ok.payout.adapter.out..")
                            .or(resideInAPackage("com.ok.payout.domain..").and(not(equivalentTo(PayoutPolicy.class)))))
            .because("배치 시작기와 Tasklet도 입력 포트로만 비즈니스를 호출해야 구현이 바뀌어도 배치 코드가 바뀌지 않는다. "
                    + "배치 크기·대기 일수 같은 정책 상수만 직접 쓴다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule application은_영속_메시징_배치_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class)
                    .or(resideInAnyPackage("org.springframework.data.jpa..", "jakarta.persistence..", "com.querydsl..",
                            "org.springframework.kafka..", "org.springframework.batch..")))
            .because("서비스는 포트에만 의존해야 영속, 메시징, 배치 기술을 바꿔도 비즈니스 코드가 바뀌지 않는다. "
                    + "Page, Pageable(org.springframework.data.domain)은 조회 결과 형식이라 허용한다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule domain은_application_adapter와_프레임워크_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class)
                    .or(resideInAnyPackage("..application..", "..adapter..",
                            "org.springframework.kafka..", "org.springframework.batch..", "org.springframework.web..")))
            .because("도메인은 바깥 레이어와 저장소, 메시징, 배치, 웹 기술을 몰라야 단독으로 이해하고 테스트할 수 있다. "
                    + "JPA, Hibernate 애노테이션과 common, 정산 예외는 허용한다 (정산 헥사고날 구조, 이슈 #441)");

    // 포트

    @ArchTest
    static final ArchRule 포트는_인터페이스다 = classes()
            .that().resideInAnyPackage("..application.port.in..", "..application.port.out..")
            .should().beInterfaces()
            .because("포트는 구현을 숨기는 경계라 인터페이스여야 한다 (정산 헥사고날 구조, 이슈 #441)");

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

    @ArchTest
    static final ArchRule ApplicationEventPublisher와_KafkaTemplate은_adapter_out_event만_사용한다 = noClasses()
            .that().resideOutsideOfPackage("..adapter.out.event..")
            .should().dependOnClassesThat().areAssignableTo(ApplicationEventPublisher.class)
            .orShould().dependOnClassesThat().areAssignableTo(KafkaTemplate.class)
            .because("서비스는 출력 포트로만 발행하고 구현체(adapter.out.event)가 발행 방식을 맡는다. 발행 방식을 바꿔도 서비스가 바뀌지 않게 한다 (정산 헥사고날 구조, 이슈 #441)");

    @ArchTest
    static final ArchRule 이벤트_클래스는_adapter_event만_사용한다 = classes()
            .that().resideInAPackage("com.ok.common.event..")
            // 받는 쪽(adapter.in.event)과 보내는 쪽(adapter.out.event)만 계약 타입을 만든다. common.event 안의 상호 참조(DomainEvent, payload)는 계약 타입 내부의 일이다
            .should().onlyBeAccessed().byAnyPackage("..adapter.in.event..", "..adapter.out.event..", "com.ok.common.event..")
            .because("모듈 간 계약 타입이 비즈니스 코드에 섞이지 않게 한다. 리스너는 이벤트를 Command나 값으로 바꿔 유스케이스를 호출하고, "
                    + "발행 포트는 값을 받는다 (정산 헥사고날 구조, 이슈 #441)");

    // 이름과 위치

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
