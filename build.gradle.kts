// 새 도메인 모듈의 뼈대를 만든다.
//   ./gradlew createModule -PmoduleName=refund -PdisplayName=환불
// 만드는 것: <모듈>/build.gradle.kts, 기본 패키지(package-info), 단독 기동 앱 클래스, ArchitectureTest 틀
// settings.gradle.kts와 monolith 의존은 직접 추가해야 한다(실행 끝에 안내를 출력한다)
tasks.register("createModule") {
    group = "help"
    description = "새 도메인 모듈의 뼈대를 만든다. -PmoduleName=<소문자 모듈 이름> [-PdisplayName=<표시 이름>]"

    val moduleName = providers.gradleProperty("moduleName")
    val displayName = providers.gradleProperty("displayName")
    val rootDir = layout.projectDirectory.asFile

    doLast {
        val name = moduleName.orNull
            ?: throw GradleException("모듈 이름이 필요하다: ./gradlew createModule -PmoduleName=refund")
        if (!Regex("[a-z]+").matches(name)) {
            throw GradleException("모듈 이름은 영문 소문자만 쓴다: $name")
        }
        val moduleDir = File(rootDir, name)
        if (moduleDir.exists()) {
            throw GradleException("이미 있는 폴더다: $moduleDir")
        }
        val appClass = name.replaceFirstChar { it.uppercase() } + "Application"
        val title = displayName.orNull ?: name

        fun write(path: String, content: String) {
            val file = File(moduleDir, path)
            file.parentFile.mkdirs()
            file.writeText(content.trimStart('\n'))
        }

        write("build.gradle.kts", """
plugins {
    id("ok.spring-module-conventions")
}
""")

        write("src/main/java/com/ok/$name/package-info.java", """
@ApplicationModule(displayName = "$title")
package com.ok.$name;

import org.springframework.modulith.ApplicationModule;
""")

        write("src/main/java/com/ok/$appClass.java", """
package com.ok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * $title 모듈을 단독으로 띄우는 앱 클래스.
 *
 * <p>Spring Modulith가 앱 클래스의 패키지를 루트로 보고 그 바로 아래 패키지를 모듈로 인식하므로 {@code com.ok}에 둔다.
 * 모듈 앱 클래스는 모듈마다 이름이 다르다. monolith에서는 다른 앱 클래스가 클래스패스에 함께 보이기 때문이다.
 * monolith는 이 클래스를 설정 클래스로 함께 스캔하므로 {@code main}과 {@code @SpringBootApplication} 외에는 얹지 않는다.
 */
@SpringBootApplication
public class $appClass {

    public static void main(String[] args) {
        SpringApplication.run($appClass.class, args);
    }
}
""")

        write("src/test/resources/archunit.properties", """
# 아직 대상 클래스가 없는 규칙이 실패하지 않게 한다.
archRule.failOnEmptyShould=false
""")

        write("src/test/java/com/ok/$name/ArchitectureTest.java", """
package com.ok.$name;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// 모듈마다 자기 구조 규칙을 가진다. 규칙은 공유 코드가 아니라 복사해서 쓰고, 모듈이 자라면 이 파일에서 바꾼다
@AnalyzeClasses(packages = "com.ok.$name", importOptions = ImportOption.DoNotIncludeTests.class)
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
            .matching("com.ok.$name.(**)")
            .should().beFreeOfCycles()
            .because("패키지가 서로 참조하면 한쪽만 따로 이해하거나 테스트하거나 분리할 수 없다");
}
""")

        println("""
            |$name 모듈 뼈대를 만들었다. 직접 할 일:
            |  1. settings.gradle.kts 의 include(...) 에 "$name" 추가
            |  2. 운영에 함께 올리려면 monolith/build.gradle.kts 에 implementation(project(":$name")) 추가
        """.trimMargin())
    }
}
