package com.ok.refund;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private static final DescribedPredicate<JavaClass> 구현하는_UseCase가_있는 = DescribedPredicate.describe(
            "implement a *UseCase of application.port.in",
            javaClass -> {
                if (javaClass.isInterface()) {
                    return false;
                }
                for (JavaClass anInterface : javaClass.getAllRawInterfaces()) {
                    if (anInterface.getPackageName().startsWith("com.ok.refund.application.port.in")
                            && anInterface.getSimpleName().endsWith("UseCase")) {
                        return true;
                    }
                }
                return false;
            });

    // 구현하는_UseCase가_있는과 달리 UseCase 인터페이스 자신도 포함한다. @Bean 메서드의 반환 타입은 보통 인터페이스라서
    private static final DescribedPredicate<JavaClass> UseCase_타입인 = DescribedPredicate.describe(
            "a *UseCase of application.port.in or its implementation",
            javaClass -> {
                if (isUseCaseInterface(javaClass)) {
                    return true;
                }
                for (JavaClass anInterface : javaClass.getAllRawInterfaces()) {
                    if (isUseCaseInterface(anInterface)) {
                        return true;
                    }
                }
                return false;
            });

    private static boolean isUseCaseInterface(JavaClass javaClass) {
        return javaClass.isInterface()
                && javaClass.getPackageName().startsWith("com.ok.refund.application.port.in")
                && javaClass.getSimpleName().endsWith("UseCase");
    }

    private static final DescribedPredicate<JavaClass> 출력_포트를_구현하는 = DescribedPredicate.describe(
            "implement a *Port of application.port.out",
            javaClass -> {
                if (javaClass.isInterface()) {
                    return false;
                }
                for (JavaClass anInterface : javaClass.getAllRawInterfaces()) {
                    if (anInterface.getPackageName().startsWith("com.ok.refund.application.port.out")) {
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
    // 환불 클래스가 놓일 수 있는 패키지를 정해 두고, 레이어 밖 패키지(루트·config·exception)는 바깥 레이어를 모르게 한다.
    // 막지 않는 길(#486): com.ok.refund 밖 패키지(예: com.ok.refundx)에 둔 클래스는 @AnalyzeClasses가 읽지 않아 모든 규칙을 피한다.
    // 막으려면 검사 범위를 패키지가 아니라 refund 모듈의 클래스 파일 전체로 바꿔야 하는데, 일부러 패키지 이름을 바꿔야만 생기고
    // 새 패키지 폴더는 PR diff에 바로 보여 리뷰에서 잡는다
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
    static final ArchRule application은_Transactional을_쓰지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().areAssignableTo(Transactional.class)
            .orShould().dependOnClassesThat().areAssignableTo(jakarta.transaction.Transactional.class)
            .because("트랜잭션은 서비스 안에서 TransactionTemplate으로 DB 작업 구간만 연다. 어노테이션은 메서드 전체를 묶어 "
                    + "다른 모듈 호출 동안에도 DB 연결과 행 잠금을 잡는다 (refund/CLAUDE.md 3절)");

    // 위 규칙은 직접 의존만 본다. @Transactional을 붙인 어노테이션을 config 등에 만들어 서비스에 달면 의존이 그 어노테이션으로만 잡혀
    // 검사를 피하므로, 어노테이션의 어노테이션까지 보는 meta 검사로 한 번 더 막는다
    @ArchTest
    static final ArchRule application_클래스는_Transactional을_감싼_어노테이션도_달지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().beMetaAnnotatedWith(Transactional.class)
            .orShould().beMetaAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("어노테이션으로 감싸도 메서드 전체가 트랜잭션으로 묶이는 것은 같다 (refund/CLAUDE.md 3절)");

    @ArchTest
    static final ArchRule application_메서드는_Transactional을_감싼_어노테이션도_달지_않는다 = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("..application..")
            .should().beMetaAnnotatedWith(Transactional.class)
            .orShould().beMetaAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("어노테이션으로 감싸도 메서드 전체가 트랜잭션으로 묶이는 것은 같다 (refund/CLAUDE.md 3절)");

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
                            "org.springframework.http.client..", "java.net.http..", "org.apache.hc..", "okhttp3..", "feign..",
                            // 직접 DB 연결(JDBC), JDK 기본 HTTP(java.net), Spring 웹(HTTP 인터페이스·웹 진입점), Redis, AWS SDK도
                            // 출력 포트를 거치지 않고 바깥에 닿는 길이라 함께 막는다. 지금 application은 이 중 아무것도 쓰지 않는다
                            "java.sql..", "javax.sql..", "java.net..", "org.springframework.web..",
                            "org.springframework.data.redis..", "software.amazon.awssdk..")))
            .because("서비스는 포트에만 의존해야 영속, 메시징, 외부 호출 기술을 바꿔도 비즈니스 코드가 바뀌지 않는다. "
                    + "페이징 묶음(org.springframework.data.domain)은 아래 규칙대로 결과 그릇 Page만 허용한다 (환불 헥사고날 구조, 이슈 #481)");

    // 쪽 크기·정렬(PageRequest, Sort)과 요청 그릇(Pageable)은 DB에서 꺼내는 방법이라 영속 어댑터가 정한다(refund/CLAUDE.md 3절).
    // 서비스·포트는 결과 그릇 Page만 쓴다
    @ArchTest
    static final ArchRule application은_페이징_묶음에서_Page만_쓴다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat(resideInAPackage("org.springframework.data.domain..")
                    .and(DescribedPredicate.not(type(Page.class))))
            .because("쪽 크기와 정렬이 서비스로 새어 들어오지 않게 한다. 결과 그릇 Page만 조회 결과 형식이라 허용한다 (refund/CLAUDE.md 3절)");

    @ArchTest
    static final ArchRule domain은_application_adapter와_프레임워크_기술에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(resideInAnyPackage("..application..", "..adapter..", "jakarta.persistence..",
                    "org.hibernate..", "org.springframework..", "org.apache.kafka..", "jakarta.servlet..", "java.sql.."))
            .because("도메인은 바깥 레이어와 저장소, 메시징, 웹 기술을 몰라야 단독으로 이해하고 테스트할 수 있다. "
                    + "java.sql(JDBC)은 아래 허용 목록의 java..에 들어가 따로 막는다(#486). "
                    + "common 예외와 환불 예외, lombok은 허용한다 (환불 헥사고날 구조, 이슈 #481)");

    // 위 금지 목록에 없는 새 기술이나 다른 모듈 타입이 도메인에 들어와도 잡도록 허용 목록으로 한 번 더 건다.
    // 막지 않는 길(#486): 배열 생성(new Foo[0])만 하는 의존은 ArchUnit이 의존으로 보지 않는다.
    // 배열을 만들기만 하고 그 타입을 쓰지 않는 도메인 코드는 실제로 생기기 어려워 규칙 대신 이 주석으로 남긴다
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

    // 입력·출력 포트의 인터페이스 이름. 값(record, enum)은 위 규칙대로 이름 제한 없이 허용한다
    @ArchTest
    static final ArchRule 입력_포트는_UseCase로_끝난다 = classes()
            .that().resideInAPackage("..application.port.in..")
            .and().areInterfaces()
            .should().haveSimpleNameEndingWith("UseCase")
            .because("이름만 보고 입력 포트를 찾고, 서비스가 구현하는 유스케이스를 구분하기 위해서다 (refund/CLAUDE.md 6절)");

    @ArchTest
    static final ArchRule 출력_포트는_Port로_끝난다 = classes()
            .that().resideInAPackage("..application.port.out..")
            .and().areInterfaces()
            .should().haveSimpleNameEndingWith("Port")
            .because("이름만 보고 출력 포트를 찾고, 어댑터가 구현하는 경계를 구분하기 위해서다 (refund/CLAUDE.md 6절)");

    @ArchTest
    static final ArchRule UseCase는_port_in에만_Port는_port_out에만_있다 = classes()
            .that().haveSimpleNameEndingWith("UseCase")
            .should().resideInAPackage("..application.port.in..")
            .andShould().beInterfaces()
            .because("UseCase 이름을 다른 곳에서 쓰면 입력 포트와 헷갈린다 (refund/CLAUDE.md 6절)");

    // 이름이 Port로 끝나는 클래스(예: SomePort 구현체)는 출력 포트 인터페이스뿐이어야 한다
    @ArchTest
    static final ArchRule Port는_port_out에만_있다 = classes()
            .that().haveSimpleNameEndingWith("Port")
            .should().resideInAPackage("..application.port.out..")
            .andShould().beInterfaces()
            .because("Port 이름을 다른 곳에서 쓰면 출력 포트와 헷갈린다 (refund/CLAUDE.md 6절)");

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

    // 위 규칙은 Spring의 발행 도구만 본다. Kafka 클라이언트(Producer 등)를 직접 쓰면 다른 출력 어댑터(예: order)에서도
    // 토픽 이름을 들고 발행할 수 있어, 메시징 라이브러리 자체를 이벤트 어댑터(받기 in.event, 보내기 out.event)에만 허용한다
    @ArchTest
    static final ArchRule 메시징_라이브러리는_이벤트_어댑터만_사용한다 = noClasses()
            .that().resideOutsideOfPackages("..adapter.in.event..", "..adapter.out.event..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.kafka..", "org.springframework.kafka..")
            .because("토픽 이름과 메시지 클래스는 이벤트 어댑터 안에만 둔다 (refund/CLAUDE.md 5절)");

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

    // @RestController만 보면 @Controller + @ResponseBody로 만든 웹 진입점이 다른 패키지에 숨을 수 있다.
    // @RestController도 @Controller를 품고 있어 meta 검사 하나로 둘 다 잡는다
    @ArchTest
    static final ArchRule 웹_컨트롤러는_Controller로_끝나고_adapter_in_web_패키지에_있다 = classes()
            .that().areMetaAnnotatedWith(Controller.class)
            .should().haveSimpleNameEndingWith("Controller")
            .andShould().resideInAPackage("..adapter.in.web..")
            .because("이름과 위치만 보고 컨트롤러를 찾을 수 있어야 한다 (컨벤션 3.2, refund/CLAUDE.md 6절)");

    @ArchTest
    static final ArchRule Service는_Service로_끝나고_application_service_패키지에_있다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should().haveSimpleNameEndingWith("Service")
            .andShould().resideInAPackage("..application.service..")
            .because("이름과 위치만 보고 서비스를 찾을 수 있어야 한다 (컨벤션 3.2, refund/CLAUDE.md 6절)");

    // 한 유스케이스의 흐름을 서비스 한 클래스에서 읽게 한다. 포트 둘을 한 서비스가 구현하면 유스케이스 경계가 흐려진다
    @ArchTest
    static final ArchRule Service는_입력_포트_UseCase를_정확히_하나_구현한다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should(new ArchCondition<JavaClass>("implement exactly one *UseCase in application.port.in") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    int count = 0;
                    for (JavaClass anInterface : javaClass.getAllRawInterfaces()) {
                        if (anInterface.getPackageName().startsWith("com.ok.refund.application.port.in")
                                && anInterface.getSimpleName().endsWith("UseCase")) {
                            count++;
                        }
                    }
                    events.add(new SimpleConditionEvent(javaClass, count == 1,
                            javaClass.getName() + " implements " + count + " UseCase(s)"));
                }
            })
            .because("입력 포트 하나를 서비스 하나가 구현해야 한 유스케이스의 흐름이 한 클래스에서 읽힌다 (refund/CLAUDE.md 3절)");

    // 위 규칙은 서비스 쪽에서 센다. 같은 UseCase를 서비스 둘이 나눠 구현하는 경우는 UseCase 쪽에서 세야 잡힌다
    @ArchTest
    static final ArchRule UseCase는_구현이_하나뿐이다 = classes()
            .that().resideInAPackage("..application.port.in..")
            .and().areInterfaces()
            .should(new ArchCondition<JavaClass>("be implemented by exactly one class") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    int count = 0;
                    for (JavaClass subclass : javaClass.getAllSubclasses()) {
                        if (!subclass.isInterface()) {
                            count++;
                        }
                    }
                    events.add(new SimpleConditionEvent(javaClass, count == 1,
                            javaClass.getName() + " is implemented by " + count + " class(es)"));
                }
            })
            .because("입력 포트 하나를 서비스 하나가 구현한다. 구현이 둘이면 어느 쪽이 주입될지 설정에 따라 갈린다 (refund/CLAUDE.md 3절)");

    // 서비스가 다른 서비스를 불러 흐름을 나누면 한 유스케이스를 한 클래스에서 읽을 수 없다.
    // 함께 쓰는 규칙은 domain(정책), DB에서 꺼내는 방법은 영속 어댑터에 두고, 승인 뒤 단계 호출은 RefundStepRunner로 부른다
    @ArchTest
    static final ArchRule 서비스는_다른_서비스를_쓰지_않는다 = noClasses()
            .that().resideInAPackage("..application.service..")
            .should().dependOnClassesThat().areAnnotatedWith(Service.class)
            .because("한 유스케이스의 흐름은 그 서비스 한 클래스에서 읽힌다 (refund/CLAUDE.md 3절)");

    // 서비스 패키지에는 유스케이스 구현(@Service)과 실패 기록 틀(RefundStepRunner)만 둔다.
    // 서비스들이 함께 쓰는 코드는 규칙이면 domain(정책), DB에서 꺼내는 방법이면 영속 어댑터로 보낸다.
    // 서비스 패키지에 보조 클래스가 생기면 규칙·흐름·기술이 섞인 "안쪽 클래스"가 되기 쉬워 이름으로 못 박는다
    @ArchTest
    static final ArchRule application_service에는_Service와_RefundStepRunner만_있다 = classes()
            .that().resideInAPackage("..application.service..")
            .and().areTopLevelClasses()
            .and().areNotAnnotatedWith(Service.class)
            .should().haveSimpleName("RefundStepRunner")
            .because("서비스 패키지는 유스케이스 흐름만 둔다. 함께 쓰는 규칙은 domain, DB 조회 방법은 영속 어댑터가 맡는다 (refund/CLAUDE.md 3절, 5절)");

    // 위 규칙은 이름만 본다. 하위 패키지(application.service.support 등)에 RefundStepRunner라는 이름의 다른 클래스를 두면 통과하므로
    // 서비스 패키지는 한 층으로만 둔다
    @ArchTest
    static final ArchRule application_service에는_하위_패키지를_두지_않는다 = classes()
            .that().resideInAPackage("..application.service..")
            .should().resideInAPackage("com.ok.refund.application.service")
            .because("하위 패키지에 이름만 같은 보조 클래스를 두어 서비스 패키지 규칙을 피하지 못하게 한다 (refund/CLAUDE.md 1절, 이슈 #486)");

    // UseCase를 구현하는 클래스가 @Service가 아니면 위 @Service 대상 규칙을 피한다
    @ArchTest
    static final ArchRule UseCase_구현체는_application_service의_Service다 = classes()
            .that(구현하는_UseCase가_있는)
            .should().beAnnotatedWith(Service.class)
            .andShould().resideInAPackage("..application.service..")
            .andShould().haveSimpleNameEndingWith("Service")
            .because("유스케이스 구현은 *Service 하나로만 둔다. 다른 이름(Facade 등)의 조율 클래스로 유스케이스를 나누지 않는다 (refund/CLAUDE.md 3절)");

    // 위 규칙은 클래스만 본다. 설정 클래스의 @Bean 메서드가 람다로 UseCase를 만들어 돌려주면 구현 클래스가 없어 모든 Service 규칙을 피한다
    @ArchTest
    static final ArchRule Bean_메서드는_UseCase를_만들지_않는다 = noMethods()
            .that().areAnnotatedWith(Bean.class)
            .and().areDeclaredInClassesThat().resideInAPackage("com.ok.refund..")
            .should().haveRawReturnType(UseCase_타입인)
            .because("유스케이스 구현은 application.service의 *Service 클래스로만 둔다 (refund/CLAUDE.md 3절, 이슈 #486)");

    @ArchTest
    static final ArchRule Entity는_JpaEntity로_끝난다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().haveSimpleNameEndingWith("JpaEntity")
            .because("JPA 엔티티는 도메인 모델과 이름으로 구분한다 (refund/CLAUDE.md 6절). 위치는 엔티티는_adapter_out_persistence_패키지에_있다가 본다");

    @ArchTest
    static final ArchRule 출력_포트_구현체는_Adapter로_끝나고_adapter_out에_있다 = classes()
            .that(출력_포트를_구현하는)
            .should().haveSimpleNameEndingWith("Adapter")
            .andShould().resideInAPackage("..adapter.out..")
            .because("외부와 닿는 구현을 이름만 보고 찾게 한다 (refund/CLAUDE.md 6절)");

    // 강제하지 않는 이름 규칙(리뷰에서 본다): *Mapper(persistence 안 변환 클래스라 이름 접미사만 관례), *Request/*Response(api.md가 DTO 위치를 정함),
    // *Command/*Query/*Result의 위치(값이 지나가는 포트 옆, 포트마다 달라 기계 검사가 어렵다), domain은 접미사 없음(접미사 없음은 검사할 수 없다)

    // 모든 모듈이 한 앱(monolith)으로 뜰 때 다른 모듈의 같은 이름 빈과 겹치면 앱이 시작되지 않는다
    // Spring Data 저장소 인터페이스는 @Component 없이도 인터페이스 이름으로 빈이 되므로 함께 본다
    @ArchTest
    static final ArchRule 스프링_빈_클래스는_이름에_Refund가_들어간다 = classes()
            .that().resideInAPackage("com.ok.refund..")
            .and(DescribedPredicate.describe("are Spring beans (@Component or Spring Data repository)",
                    (JavaClass javaClass) -> javaClass.isMetaAnnotatedWith(Component.class)
                            || javaClass.isAssignableTo(org.springframework.data.repository.Repository.class)))
            .should().haveSimpleNameContaining("Refund")
            .because("빈 이름이 다른 모듈과 겹치면 monolith가 시작되지 않는다. @Service, @Repository, @RestController, "
                    + "@Configuration은 모두 @Component를 포함한다 (refund/CLAUDE.md 6절)");

    // 위 규칙은 클래스 이름만 본다. @Service("orderService")처럼 이름을 직접 적으면 클래스 이름과 상관없이 그 이름이 빈 이름이 된다.
    // 빈 이름은 소문자로 시작해(refundOrderHttpAdapter) 대소문자를 가리지 않고 본다.
    // value가 문자열일 때만 빈 이름으로 본다. @RestControllerAdvice·@ControllerAdvice의 value는 basePackages(String[])의 별칭이라,
    // 그대로 읽으면 이름을 적지 않아도 배열 문자열이 빈 이름처럼 보여 정상 코드를 막는다(#486 리뷰에서 실행으로 확인).
    // 대신 이 둘은 name 속성으로 빈 이름을 정하므로(common의 ApiControllerAdvice와 겹칠 수 있음) 문자열인 name도 함께 본다
    @ArchTest
    static final ArchRule 직접_적은_빈_이름에도_refund가_들어간다 = classes()
            .that().resideInAPackage("com.ok.refund..")
            .should(new ArchCondition<JavaClass>("have no explicit bean name, or one containing refund") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaAnnotation<JavaClass> annotation : javaClass.getAnnotations()) {
                        JavaClass annotationType = annotation.getRawType();
                        if (!annotationType.isEquivalentTo(Component.class) && !annotationType.isMetaAnnotatedWith(Component.class)) {
                            continue;
                        }
                        for (String attribute : List.of("value", "name")) {
                            if (!(annotation.get(attribute).orElse(null) instanceof String beanName)) {
                                continue;
                            }
                            boolean ok = beanName.isEmpty() || containsRefund(beanName);
                            events.add(new SimpleConditionEvent(javaClass, ok, javaClass.getName() + " declares bean name '"
                                    + beanName + "' in @" + annotationType.getSimpleName() + "." + attribute));
                        }
                    }
                }
            })
            .because("빈 이름이 다른 모듈과 겹치면 monolith가 시작되지 않는다 (refund/CLAUDE.md 6절, 이슈 #486)");

    // @Bean 메서드로 만든 빈은 이름을 적지 않으면 메서드 이름이 빈 이름이다(refundFlyway). 위 클래스 규칙이 보지 못한다
    @ArchTest
    static final ArchRule Bean_메서드의_빈_이름에도_refund가_들어간다 = methods()
            .that().areAnnotatedWith(Bean.class)
            .and().areDeclaredInClassesThat().resideInAPackage("com.ok.refund..")
            .should(new ArchCondition<JavaMethod>("have a bean name containing refund") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    Bean bean = method.getAnnotationOfType(Bean.class);
                    List<String> beanNames = new ArrayList<>();
                    beanNames.addAll(List.of(bean.name()));
                    beanNames.addAll(List.of(bean.value()));
                    if (beanNames.isEmpty()) {
                        beanNames.add(method.getName());
                    }
                    for (String beanName : beanNames) {
                        boolean ok = containsRefund(beanName);
                        events.add(new SimpleConditionEvent(method, ok,
                                method.getFullName() + " creates bean '" + beanName + "'"));
                    }
                }
            })
            .because("빈 이름이 다른 모듈과 겹치면 monolith가 시작되지 않는다 (refund/CLAUDE.md 6절, 이슈 #486)");

    private static boolean containsRefund(String beanName) {
        return beanName.toLowerCase(Locale.ROOT).contains("refund");
    }

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
