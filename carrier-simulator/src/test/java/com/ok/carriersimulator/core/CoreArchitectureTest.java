package com.ok.carriersimulator.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// core는 서버 없이도 시험에서 직접 쓸 수 있어야 한다. Spring이나 바깥 패키지에 기대면 그럴 수 없다
@AnalyzeClasses(packages = "com.ok.carriersimulator", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

    @ArchTest
    static final ArchRule core는_Spring에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("com.ok.carriersimulator.core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @ArchTest
    static final ArchRule core는_바깥_패키지에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("com.ok.carriersimulator.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ok.carriersimulator.config..",
                    "com.ok.carriersimulator.web..",
                    "com.ok.carriersimulator.webhook..");
}
