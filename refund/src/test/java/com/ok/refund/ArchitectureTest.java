package com.ok.refund;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// 모듈마다 자기 구조 규칙을 가진다. 규칙은 공유 코드가 아니라 복사해서 쓰고, 모듈이 자라면 이 파일에서 바꾼다
@AnalyzeClasses(packages = "com.ok.refund", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule 컨트롤러_서비스_리포지토리_도메인_순서로만_의존한다 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true) // 아직 없는 레이어가 있어도 실패하지 않는다
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Domain").definedBy("..domain..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Service", "Repository")
            .because("레이어가 한 방향으로만 의존해야 한 레이어의 변경이 다른 레이어로 번지지 않는다. 엔티티는 서비스 밖으로 내보내지 않는다 (컨벤션 2.1, 2.3)");

    @ArchTest
    static final ArchRule 패키지_간_순환_의존이_없다 = slices()
            .matching("com.ok.refund.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다");
}
