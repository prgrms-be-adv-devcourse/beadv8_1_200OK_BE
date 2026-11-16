package com.ok.common.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;

class JpaMetricsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(JpaMetricsConfig.class);

    @Test
    void 기본값은_켜져_있어_통계와_SQL_카운터와_필터를_등록한다() {
        runner.run(context -> {
            // given
            Map<String, Object> properties = new HashMap<>();

            // when
            context.getBean(HibernatePropertiesCustomizer.class).customize(properties);

            // then
            assertSoftly(softly -> {
                softly.assertThat(properties.get(AvailableSettings.GENERATE_STATISTICS)).isEqualTo(true);
                softly.assertThat(properties.get(AvailableSettings.STATEMENT_INSPECTOR))
                        .isInstanceOf(QueryCountStatementInspector.class);
                softly.assertThat(context.containsBean("queryCountFilterRegistration")).isTrue();
            });
        });
    }

    @Test
    void 토글을_끄면_통계도_필터도_방언_교체도_등록하지_않는다() {
        runner.withPropertyValues("app.metrics.jpa.enabled=false").run(context ->
                assertSoftly(softly -> {
                    softly.assertThat(context.getBeanNamesForType(HibernatePropertiesCustomizer.class)).isEmpty();
                    softly.assertThat(context.containsBean("queryCountFilterRegistration")).isFalse();
                    softly.assertThat(context.containsBean("optimisticLockCountingDialectInstaller")).isFalse();
                }));
    }

    @Test
    void 방언이_지정되지_않은_EntityManagerFactory에는_충돌을_세는_방언을_설치한다() {
        runner.run(context -> {
            // given
            BeanPostProcessor installer = context.getBean("optimisticLockCountingDialectInstaller", BeanPostProcessor.class);
            LocalContainerEntityManagerFactoryBean sut = new LocalContainerEntityManagerFactoryBean();

            // when
            installer.postProcessBeforeInitialization(sut, "entityManagerFactory");

            // then
            assertThat(sut.getJpaDialect()).isInstanceOf(OptimisticLockCountingJpaDialect.class);
        });
    }

    @Test
    void 이미_방언이_지정된_EntityManagerFactory는_덮어쓰지_않는다() {
        runner.run(context -> {
            // given
            BeanPostProcessor installer = context.getBean("optimisticLockCountingDialectInstaller", BeanPostProcessor.class);
            HibernateJpaDialect custom = new HibernateJpaDialect();
            LocalContainerEntityManagerFactoryBean sut = new LocalContainerEntityManagerFactoryBean();
            sut.setJpaDialect(custom);

            // when
            installer.postProcessBeforeInitialization(sut, "entityManagerFactory");

            // then
            assertThat(sut.getJpaDialect()).isSameAs(custom);
        });
    }

    @Test
    void EntityManagerFactory가_아닌_빈은_그대로_둔다() {
        runner.run(context -> {
            // given
            BeanPostProcessor installer = context.getBean("optimisticLockCountingDialectInstaller", BeanPostProcessor.class);
            Object other = new Object();

            // when
            Object sut = installer.postProcessBeforeInitialization(other, "other");

            // then
            assertThat(sut).isSameAs(other);
        });
    }
}
