plugins {
    base // check 태스크를 두어 verifyInitDb를 build에 물린다
}

// DB 스키마를 만드는 초기화 스크립트(deploy/initdb)의 모듈 목록은 settings.gradle.kts의 모듈에서 만든다. 직접 고치지 않는다
// 도메인 모듈은 common, monolith를 뺀 서브프로젝트이고 스키마 이름은 paldogotgan_<모듈 이름>이다
//   ./gradlew generateInitDb   모듈을 추가·삭제한 뒤 실행해 deploy/initdb/modules.txt를 갱신한다
//   ./gradlew check            modules.txt가 모듈 목록과 같은지 검사한다(verifyInitDb)
val initDbModulesFile = layout.projectDirectory.file("deploy/initdb/modules.txt")
val initDbModulesContent = subprojects.map { it.name }
    .filterNot { it == "common" || it == "monolith" }
    .sorted()
    .joinToString(
        separator = "\n",
        prefix = "# 도메인 모듈 목록. settings.gradle.kts의 모듈에서 ./gradlew generateInitDb가 만든다. 직접 고치지 않는다.\n",
        postfix = "\n",
    )

val generateInitDb = tasks.register("generateInitDb") {
    group = "build setup"
    description = "deploy/initdb/modules.txt를 도메인 모듈 목록으로 다시 만든다"
    inputs.property("content", initDbModulesContent)
    outputs.file(initDbModulesFile)
    doLast {
        initDbModulesFile.asFile.writeText(initDbModulesContent)
    }
}

val verifyInitDb = tasks.register("verifyInitDb") {
    group = "verification"
    description = "deploy/initdb/modules.txt가 도메인 모듈 목록과 같은지 검사한다"
    inputs.property("content", initDbModulesContent)
    // 파일 내용은 매번 읽어 비교해야 하므로 up-to-date로 건너뛰지 않는다. 같이 실행하면 generateInitDb가 먼저 돈다
    outputs.upToDateWhen { false }
    mustRunAfter(generateInitDb)
    doLast {
        val file = initDbModulesFile.asFile
        if (!file.exists() || file.readText() != initDbModulesContent) {
            throw GradleException("deploy/initdb/modules.txt가 도메인 모듈 목록과 다르다. ./gradlew generateInitDb 를 실행해 갱신한다. 모듈을 추가하면 새 스키마가 만들어지지 않는다")
        }
    }
}

tasks.check {
    dependsOn(verifyInitDb)
}

// 새 도메인 모듈의 뼈대를 만든다.
//   ./gradlew createModule -PmoduleName=notification -PdisplayName=알림
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

dependencies {
    implementation(project(":common"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))
    // 이 모듈만 쓰는 의존성은 여기에 더한다. 스타터는 common이 api로 열어 두었다
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

        write(".env.example", """
# $name 모듈을 단독으로 실행할 때 쓰는 DB 계정. 이 모듈 스키마(paldogotgan_$name)에만 권한을 갖는다
# .env로 복사해 쓴다(.env는 git에 올라가지 않는다). 루트에서 ./infra/mysql/sync-module-users.sh --init 을 실행하면
# 이 파일에서 .env를 만들고(비밀번호는 무작위) MySQL에 같은 계정을 만든다
# monolith는 이 파일을 읽지 않고 루트 .env의 공용 계정을 쓴다
DB_USERNAME=paldogotgan_$name
DB_PASSWORD=
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
            |  2. ./gradlew generateInitDb 로 DB 스키마 목록(deploy/initdb/modules.txt) 갱신 (이미 만든 로컬 DB 볼륨에는 스키마를 직접 만들어야 한다)
            |     그다음 ./infra/mysql/sync-module-users.sh --init 으로 모듈 DB 계정(.env)을 만든다
            |  3. 운영에 함께 올리려면 monolith/build.gradle.kts 에 implementation(project(":$name")) 추가
        """.trimMargin())
    }
}
