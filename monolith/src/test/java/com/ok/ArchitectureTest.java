package com.ok;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// 모든 모듈의 클래스가 클래스패스에 있는 monolith에서만 모듈에 걸친 규칙을 한 번에 검사할 수 있다
@AnalyzeClasses(packages = "com.ok", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule Redis_클라이언트는_persistence_redis_패키지만_사용한다 = noClasses()
            .that().resideOutsideOfPackage("com.ok.common.persistence.redis..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..")
            .because("Redis에 닿는 코드를 한 곳에 모아야 클라이언트나 저장소를 바꿀 때 그 패키지만 바뀐다. "
                    + "서비스는 CacheStore 인터페이스에만 의존한다 (#538)");
}
