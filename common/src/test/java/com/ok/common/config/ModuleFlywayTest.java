package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

// Flyway는 load()만으로는 DB에 접속하지 않으므로 설정 값만 확인한다
class ModuleFlywayTest {

    private static final String SCHEMA = "paldogotgan_media";

    private final DataSource dataSource = new DriverManagerDataSource("jdbc:mysql://localhost:3306/unused");

    @Test
    void 모듈_Flyway는_자기_스키마와_자기_마이그레이션_폴더만_쓴다() {
        // given
        MockEnvironment environment = new MockEnvironment();

        // when
        Flyway sut = ModuleFlyway.create(dataSource, environment, SCHEMA, "media");

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getConfiguration().getLocations()).extracting(Location::getPath)
                    .containsExactly("db/migration/media");
            softly.assertThat(sut.getConfiguration().getSchemas()).containsExactly(SCHEMA);
            softly.assertThat(sut.getConfiguration().getDefaultSchema()).isEqualTo(SCHEMA);
            softly.assertThat(sut.getConfiguration().isCreateSchemas()).isFalse();
            softly.assertThat(sut.getConfiguration().getTable()).isEqualTo("flyway_schema_history");
        });
    }

    @Test
    void 모듈_단독_모드여도_모듈_Flyway의_위치와_이력_테이블은_monolith와_같다() {
        // given: 단독 모드에서 common 마이그레이션이 모듈 이력에 섞이면 같은 DB를 monolith로 띄울 때 검증이 실패한다
        MockEnvironment standalone = new MockEnvironment().withProperty(ModuleFlyway.STANDALONE_PROPERTY, "true");
        MockEnvironment monolith = new MockEnvironment();

        // when
        Flyway standaloneFlyway = ModuleFlyway.create(dataSource, standalone, SCHEMA, "media");
        Flyway monolithFlyway = ModuleFlyway.create(dataSource, monolith, SCHEMA, "media");

        // then
        assertSoftly(softly -> {
            softly.assertThat(standaloneFlyway.getConfiguration().getLocations())
                    .isEqualTo(monolithFlyway.getConfiguration().getLocations());
            softly.assertThat(standaloneFlyway.getConfiguration().getTable())
                    .isEqualTo(monolithFlyway.getConfiguration().getTable());
        });
    }

    @Test
    void 테스트용_단일_이력_모드에서는_모듈_Flyway가_common_마이그레이션도_적용한다() {
        // given
        MockEnvironment environment = new MockEnvironment().withProperty(ModuleFlyway.SINGLE_HISTORY_PROPERTY, "true");

        // when
        Flyway sut = ModuleFlyway.create(dataSource, environment, SCHEMA, "media");

        // then
        assertThat(sut.getConfiguration().getLocations()).extracting(Location::getPath)
                .containsExactlyInAnyOrder("db/migration/media", "db/migration/common");
    }

    @Test
    void 단독_모드의_common_Flyway는_common_마이그레이션을_별도_이력_테이블에_적용한다() {
        // given
        MockEnvironment environment = new MockEnvironment().withProperty(ModuleFlyway.STANDALONE_PROPERTY, "true");

        // when
        Flyway sut = ModuleFlyway.createStandaloneCommon(dataSource, environment);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getConfiguration().getLocations()).extracting(Location::getPath)
                    .containsExactly("db/migration/common");
            softly.assertThat(sut.getConfiguration().getTable()).isEqualTo(ModuleFlyway.STANDALONE_COMMON_TABLE);
            softly.assertThat(sut.getConfiguration().isCreateSchemas()).isFalse();
            // 모듈 마이그레이션이 먼저 끝나 스키마가 비어 있지 않으므로 기준선을 잡고 시작한다
            softly.assertThat(sut.getConfiguration().isBaselineOnMigrate()).isTrue();
            softly.assertThat(sut.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
        });
    }

    @Test
    void 기본_스키마의_common_Flyway는_모르는_이력이_있으면_실패하도록_무시_패턴을_비운다() {
        // given
        MockEnvironment environment = new MockEnvironment();

        // when
        Flyway sut = ModuleFlyway.createCommon(dataSource, environment);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getConfiguration().getIgnoreMigrationPatterns()).isEmpty();
            softly.assertThat(sut.getConfiguration().getTable()).isEqualTo("flyway_schema_history");
            softly.assertThat(sut.getConfiguration().getLocations()).extracting(Location::getPath)
                    .containsExactly("db/migration/common");
        });
    }

    @Test
    void spring_flyway_속성의_out_of_order와_validate_on_migrate를_모든_인스턴스에_넣는다() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.flyway.out-of-order", "true")
                .withProperty("spring.flyway.validate-on-migrate", "false");

        // when
        Flyway module = ModuleFlyway.create(dataSource, environment, SCHEMA, "media");
        Flyway common = ModuleFlyway.createCommon(dataSource, environment);
        Flyway standaloneCommon = ModuleFlyway.createStandaloneCommon(dataSource, environment);

        // then
        assertThat(new Flyway[] {module, common, standaloneCommon}).allSatisfy(flyway -> {
            assertThat(flyway.getConfiguration().isOutOfOrder()).isTrue();
            assertThat(flyway.getConfiguration().isValidateOnMigrate()).isFalse();
        });
    }

    @Test
    void 속성이_없으면_out_of_order는_끄고_validate_on_migrate는_켠다() {
        // given
        MockEnvironment environment = new MockEnvironment();

        // when
        Flyway sut = ModuleFlyway.create(dataSource, environment, SCHEMA, "media");

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getConfiguration().isOutOfOrder()).isFalse();
            softly.assertThat(sut.getConfiguration().isValidateOnMigrate()).isTrue();
        });
    }
}
