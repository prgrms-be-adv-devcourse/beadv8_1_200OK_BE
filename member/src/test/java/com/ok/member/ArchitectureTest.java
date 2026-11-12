package com.ok.member;

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
import org.springframework.kafka.core.KafkaOperations;
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
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;

import com.ok.common.exception.ErrorCode;
import com.ok.member.auth.application.service.LoginFacade;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.persistence.Entity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

@AnalyzeClasses(packages = {"com.ok.member", "com.ok.common.event"}, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // 이벤트 발행 도구. 발행 구현체를 알아보는 조건과 이벤트_발행_도구는_adapter_out만_사용한다 규칙이 함께 써서,
    // 도구를 더할 때 한쪽만 고치는 일이 없게 한다.
    // KafkaTemplate 대신 상위 인터페이스(KafkaOperations)로 받아도 걸리도록 KafkaOperations 기준으로 검사한다
    private static final DescribedPredicate<JavaClass> 발행_도구 = assignableTo(ApplicationEventPublisher.class)
            .or(assignableTo(KafkaOperations.class))
            .as("ApplicationEventPublisher or KafkaOperations");

    // 발행 도구를 직접 쓰는 클래스 = 발행 구현체로 본다
    private static final DescribedPredicate<JavaClass> 발행_도구에_의존하는 = DescribedPredicate.describe(
            "use " + 발행_도구.getDescription(),
            javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> 발행_도구.test(dependency.getTargetClass())));

    // member 모듈의 하위 도메인. 아래 헥사고날 규칙을 하위 도메인마다 검사한다.
    // 새 하위 도메인을 만들면 여기에 더한다. 빠뜨리면 하위_도메인_목록_밖에_클래스를_두지_않는다 규칙이 실패한다
    private static final List<Subdomain> SUBDOMAINS = List.of(
            new Subdomain("seller"), new Subdomain("member"), new Subdomain("auth"));

    // 다른 하위 도메인을 부를 수 있는 위치. 하위 도메인 사이 호출을 출력 어댑터에만 두어 의존이 한 곳에서 보이게 한다
    private static final String ANY_SUBDOMAIN_ADAPTER_OUT = "com.ok.member.*.adapter.out..";
    // 모듈 최상위 패키지(하위 패키지 제외). 다른 모듈에 공개하는 클래스를 두는 곳이라(CLAUDE.md 모듈 경계) 유스케이스를 부를 수 있다
    private static final String MODULE_ROOT = "com.ok.member";
    // 하위 도메인에 속하지 않는 모듈 전용 설정(Flyway). 하위 도메인 밖에 둘 수 있는 유일한 하위 패키지다
    private static final String MODULE_CONFIG = "com.ok.member.config..";
    private static final String[] SUBDOMAIN_PACKAGES = SUBDOMAINS.stream()
            .map(Subdomain::all)
            .toArray(String[]::new);

    // 헥사고날 구조 (delivery ArchitectureTest와 같은 규칙을 하위 도메인 범위로 검사한다)

    // 아래 규칙 대부분은 SUBDOMAINS의 패키지만 검사하므로, 목록에 없는 하위 도메인은 구조 규칙에 걸리지 않는다
    @ArchTest
    static final ArchRule 하위_도메인_목록_밖에_클래스를_두지_않는다 = classes()
            .that().resideInAPackage("com.ok.member..")
            .should().resideInAnyPackage(concat(SUBDOMAIN_PACKAGES, MODULE_ROOT, MODULE_CONFIG))
            .because("새 하위 도메인을 SUBDOMAINS에 더하지 않으면 헥사고날 규칙이 아무것도 검사하지 않은 채 통과한다. "
                    + "모듈 최상위는 다른 모듈에 공개하는 클래스, config는 모듈 전용 설정 자리다 (member 헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 헥사고날_레이어는_한_방향으로만_의존한다 = eachSubdomain(subdomain -> layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy(subdomain.pkg("domain"))
            .layer("Application").definedBy(subdomain.pkg("application"))
            .layer("AdapterIn").definedBy(subdomain.pkg("adapter.in"))
            .layer("AdapterOut").definedBy(subdomain.pkg("adapter.out"))
            .whereLayer("AdapterIn").mayNotBeAccessedByAnyLayer()
            .whereLayer("AdapterOut").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("AdapterIn", "AdapterOut")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "AdapterOut")
            .because("진입점(in)과 외부 연결(out)이 서비스를 거쳐서만 만나야 연동 방식을 바꿔도 비즈니스 코드가 바뀌지 않는다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    @ArchTest
    static final ArchRule 다른_하위_도메인은_adapter_out에서_유스케이스_포트와_dto만_쓴다 = eachSubdomain(subdomain -> CompositeArchRule
            .of(classes()
                    .that().resideInAPackage(subdomain.all())
                    .and().resideOutsideOfPackages(subdomain.pkg("application.port.in"), subdomain.pkg("application.dto"))
                    // onlyBeAccessed는 호출·필드 접근만 보므로, 타입 선언이나 클래스 참조까지 잡도록 의존 전체를 본다
                    .should().onlyHaveDependentClassesThat().resideInAPackage(subdomain.all()))
            .and(classes()
                    .that().resideInAnyPackage(subdomain.pkg("application.port.in"), subdomain.pkg("application.dto"))
                    .should().onlyHaveDependentClassesThat().resideInAnyPackage(subdomain.all(), ANY_SUBDOMAIN_ADAPTER_OUT, MODULE_ROOT))
            .because("다른 하위 도메인은 자기 출력 어댑터(adapter.out)에서 유스케이스로만 부른다. 하위 도메인 사이 의존을 어댑터 한 곳에 모아 "
                    + "상대 유스케이스나 DTO가 바뀌어도 서비스가 바뀌지 않게 한다. 모듈 최상위의 공개 클래스는 예외다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    // 포트는 JPA를 몰라 @Lock을 붙일 수 없다. *JpaRepository에서 다시 선언하지 않으면 Spring Data가 이름만 보고
    // 잠금 없는 조회를 만들어 오류 없이 돈다. wallet ArchitectureTest(#505)와 같은 규칙을 member의 모든 하위 도메인에 건다.
    // 잠금 조회는 이름(ForUpdate, ForShare)으로 찾고, 이름과 잠금 종류가 맞는지도 본다
    @ArchTest
    static final ArchRule 잠금_조회는_JPA_저장소에_Lock과_함께_다시_선언한다 = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("com.ok.member.*.application.port.out..")
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
            .because("잠금이 빠진 조회는 동시에 요청이 몰릴 때만 드러난다. Refresh Token이면 같은 토큰으로 재발급이 두 번 성공한다. 빌드에서 바로 실패하게 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)");

    // #425: 로그인은 트랜잭션 없이 bcrypt를 비교하고, DB 작업은 AuthService의 짧은 트랜잭션에 맡긴다.
    // LoginFacade가 트랜잭션을 갖거나 출력 포트를 직접 부르면 요청 하나가 커넥션을 오래 쥐거나 둘을 쥐어 커넥션 데드락이 다시 생긴다
    @ArchTest
    static final ArchRule 로그인_Facade는_트랜잭션_없이_AuthService로만_DB에_닿는다 = CompositeArchRule
            .of(noClasses()
                    .that().haveFullyQualifiedName(LoginFacade.class.getName())
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.ok.member.auth.application.port.out..", "com.ok.member.auth.adapter..")
                    .orShould().beAnnotatedWith(Transactional.class)
                    .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class))
            .and(noMethods()
                    .that().areDeclaredInClassesThat().haveFullyQualifiedName(LoginFacade.class.getName())
                    .should().beAnnotatedWith(Transactional.class)
                    .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class))
            .because("bcrypt 비교 동안 DB 커넥션을 잡지 않아야 로그인이 몰려도 커넥션 풀이 마르지 않는다 "
                    + "(docs/decisions/member/425-login-connection-deadlock.md)");

    @ArchTest
    static final ArchRule 입력_어댑터는_유스케이스_포트와_dto만_안다 = eachSubdomain(subdomain -> noClasses()
            .that().resideInAPackage(subdomain.pkg("adapter.in"))
            .should().dependOnClassesThat().resideInAnyPackage(
                    subdomain.pkg("application.service"), subdomain.pkg("application.port.out"),
                    subdomain.pkg("adapter.out"), subdomain.pkg("domain"))
            .because("컨트롤러와 리스너는 외부 입력의 진입점이다. 트랜잭션과 도메인 처리는 유스케이스 구현에 맡기고, 구현이 바뀌어도 진입점이 바뀌지 않게 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    @ArchTest
    static final ArchRule application은_영속_기술에_의존하지_않는다 = eachSubdomain(subdomain -> noClasses()
            .that().resideInAPackage(subdomain.pkg("application"))
            .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class)
                    .or(resideInAnyPackage("org.springframework.data.jpa..", "jakarta.persistence..", "com.querydsl..")))
            .because("서비스는 저장소 포트에만 의존해야 영속 기술을 바꿔도 비즈니스 코드가 바뀌지 않는다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    @ArchTest
    static final ArchRule application은_토큰_서명_기술에_의존하지_않는다 = eachSubdomain(subdomain -> noClasses()
            .that().resideInAPackage(subdomain.pkg("application"))
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.security.oauth2.jwt..", "com.nimbusds..")
            .because("서비스는 토큰 발급 포트에만 의존하고 서명 방식(JWT, 키, 헤더)은 adapter.out이 맡는다. 서명 기술을 바꿔도 서비스가 바뀌지 않게 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    @ArchTest
    static final ArchRule 포트는_인터페이스다 = eachSubdomain(subdomain -> classes()
            .that().resideInAnyPackage(subdomain.pkg("application.port.in"), subdomain.pkg("application.port.out"))
            .should().beInterfaces()
            .because("포트는 구현을 숨기는 경계라 인터페이스여야 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    @ArchTest
    static final ArchRule 입력_포트는_도메인을_모른다 = eachSubdomain(subdomain -> noClasses()
            .that().resideInAPackage(subdomain.pkg("application.port.in"))
            .should().dependOnClassesThat().resideInAPackage(subdomain.pkg("domain"))
            .because("입력 포트는 진입점에 공개하는 약속이다. 엔티티를 돌려주면 진입점이 도메인을 알게 되고, 트랜잭션 밖에서 바꾼 값은 오류 없이 저장되지 않는다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    @ArchTest
    static final ArchRule 출력_포트는_Spring_Data_Repository를_상속하지_않는다 = eachSubdomain(subdomain -> noClasses()
            .that().resideInAPackage(subdomain.pkg("application.port.out"))
            .should().beAssignableTo(org.springframework.data.repository.Repository.class)
            .because("포트가 Repository를 상속하면 Spring Data가 포트 자체로 저장소를 하나 더 만들어 빈이 둘이 된다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    @ArchTest
    static final ArchRule 출력_포트의_구현은_adapter_out에_있다 = eachSubdomain(subdomain -> classes()
            .that().areAssignableTo(resideInAPackage(subdomain.pkg("application.port.out")))
            .and().resideOutsideOfPackage(subdomain.pkg("application.port.out"))
            .should().resideInAPackage(subdomain.pkg("adapter.out"))
            .because("외부 시스템과 닿는 구현은 adapter.out에 모아 바뀌는 범위가 경로로 드러나게 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    // 하위 도메인 밖(모듈 최상위, config)도 검사하도록 모듈 전체를 본다
    @ArchTest
    static final ArchRule 이벤트_발행_도구는_adapter_out만_사용한다 = noClasses()
            .that().resideInAPackage("com.ok.member..")
            .and().resideOutsideOfPackage(ANY_SUBDOMAIN_ADAPTER_OUT)
            .should().dependOnClassesThat(발행_도구)
            .because("서비스는 출력 포트로만 발행하고 구현체(adapter.out)가 발행 방식을 맡는다. "
                    + "발행 방식(스프링 이벤트, Kafka)을 바꿔도 서비스가 바뀌지 않게 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule 이벤트_클래스는_adapter만_사용한다 = classes()
            .that().resideInAPackage("com.ok.common.event..")
            // common.event 안에서 서로 참조하는 것(DomainEvent, payload)은 모듈 간 계약 타입 내부의 일이다.
            // onlyBeAccessed는 호출·필드 접근만 보므로, 필드·매개변수 타입으로 쓰는 것까지 잡도록 의존 전체를 본다
            .should().onlyHaveDependentClassesThat().resideInAnyPackage("com.ok.member.*.adapter..", "com.ok.common.event..")
            .because("모듈 간 계약 타입이 비즈니스 코드에 섞이지 않게 한다. 리스너는 이벤트를 Command나 값으로 바꿔 유스케이스를 호출하고, "
                    + "발행 포트는 값을 받는다 (member 헥사고날 구조, docs/DEVELOPMENT.md)");

    // 발행하는 하위 도메인 목록을 따로 두지 않도록 모듈 전체를 본다. 하위 도메인마다 걸면 발행하지 않는 auth가 빈 규칙으로 실패하고,
    // 목록으로 고르면 새로 발행하는 하위 도메인을 빠뜨렸을 때 검사를 건너뛴다.
    // 다른 하위 도메인의 출력 포트를 구현하는 것은 다른_하위_도메인은_adapter_out에서_유스케이스_포트와_dto만_쓴다 규칙이 막는다
    @ArchTest
    static final ArchRule 이벤트_발행_구현체는_출력_포트를_구현한다 = classes()
            .that(발행_도구에_의존하는)
            // adapter.out 밖에서 쓰는 경우는 이벤트_발행_도구는_adapter_out만_사용한다 규칙이 막는다
            .and().resideInAPackage(ANY_SUBDOMAIN_ADAPTER_OUT)
            .should().implement(resideInAPackage("com.ok.member.*.application.port.out.."))
            // 발행 구현체가 있으므로, 패턴을 잘못 적어 아무것도 검사하지 않은 채 통과하지 않게 한다.
            // 이 규칙이 "검사한 클래스가 없다"로 실패하면 member가 이벤트 발행을 모두 없앤 것이니 이 규칙도 지운다
            .allowEmptyShould(false)
            .because("서비스는 출력 포트만 알고, adapter.out의 구현체가 발행 방식을 맡는다. "
                    + "발행 방식(스프링 이벤트, Kafka)을 바꿀 때 구현체만 바꾸게 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)");

    @ArchTest
    static final ArchRule RestController는_Controller로_끝나고_adapter_in_web_패키지에_있다 = eachSubdomain(subdomain -> classes()
            .that().areAnnotatedWith(RestController.class)
            .and().resideInAPackage(subdomain.all())
            .should().haveSimpleNameEndingWith("Controller")
            .andShould().resideInAPackage(subdomain.pkg("adapter.in.web"))
            .because("이름과 위치만 보고 컨트롤러를 찾을 수 있어야 한다 (컨벤션 3.2, member 헥사고날 구조)"));

    @ArchTest
    static final ArchRule 리포지토리는_adapter_out_persistence_패키지에_있다 = eachSubdomain(subdomain -> classes()
            .that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
            .and().resideInAPackage(subdomain.all())
            .should().resideInAPackage(subdomain.pkg("adapter.out.persistence"))
            .because("영속성 구현은 adapter.out.persistence에 모아 역할별로 찾기 쉽게 한다 (member 헥사고날 구조, docs/DEVELOPMENT.md)"));

    // 같은 규칙을 옮긴 하위 도메인마다 만들어 하나로 묶는다. 실패하면 메시지에 어느 하위 도메인 패키지인지 나온다.
    // 모듈 설정(archRule.failOnEmptyShould=false)은 대상이 없는 규칙을 통과시키므로, 여기서는 대상이 없으면 실패하게 한다.
    // 패키지 이름을 잘못 적었거나 옮긴 하위 도메인에 해당 클래스가 없으면 규칙이 아무것도 검사하지 않은 채 통과하기 때문이다
    private static ArchRule eachSubdomain(Function<Subdomain, ArchRule> rule) {
        return CompositeArchRule.of(SUBDOMAINS.stream()
                .map(rule)
                .map(subdomainRule -> subdomainRule.allowEmptyShould(false))
                .toList());
    }

    private static String[] concat(String[] packages, String... more) {
        return Stream.concat(Arrays.stream(packages), Arrays.stream(more)).toArray(String[]::new);
    }

    private record Subdomain(String name) {

        String all() {
            return "com.ok.member." + name + "..";
        }

        String pkg(String subPackage) {
            return "com.ok.member." + name + "." + subPackage + "..";
        }

    }

    // 모듈 전체

    @ArchTest
    static final ArchRule 패키지_간_순환_의존이_없다 = slices()
            .matching("com.ok.member.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다. "
                    + "모듈 간 순환 금지(컨벤션 2.2)를 모듈 안 패키지까지 넓혀 적용한다");

    // 이름과 위치

    @ArchTest
    static final ArchRule Service는_Service로_끝나고_application_service_패키지에_있다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should().haveSimpleNameEndingWith("Service")
            .andShould().resideInAPackage("com.ok.member.*.application.service..")
            .because("이름과 위치만 보고 서비스를 찾을 수 있어야 한다 (컨벤션 3.2, member 헥사고날 구조)");

    @ArchTest
    static final ArchRule 엔티티는_domain_패키지에_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("com.ok.member.*.domain..")
            .because("엔티티가 domain 패키지에 있어야 레이어 규칙이 엔티티 의존을 검사할 수 있다 (member 헥사고날 구조)");

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
            .should(new ArchCondition<JavaClass>("be annotated with @Table(catalog = MemberSchema.NAME)") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    boolean ok = javaClass.tryGetAnnotationOfType(Table.class)
                            .map(table -> MemberSchema.NAME.equals(table.catalog()))
                            .orElse(false);
                    events.add(new SimpleConditionEvent(javaClass, ok,
                            javaClass.getName() + (ok ? " has" : " does not have") + " @Table(catalog = MemberSchema.NAME)"));
                }
            })
            .because("모듈은 자기 MySQL 스키마(MemberSchema.NAME)에 테이블을 둔다. 엔티티마다 catalog로 지정해야 한다");
}
