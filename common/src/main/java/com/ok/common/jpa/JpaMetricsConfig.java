package com.ok.common.jpa;

import io.micrometer.core.instrument.MeterRegistry;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * JPA 관측에 필요한 설정을 한 곳에서 켠다. 모듈 yaml마다 같은 속성을 두지 않으려고 Java로 등록한다.
 *
 * <p>통계를 켜면 {@code hibernate.*} 지표가 노출된다(Boot가 {@code hibernate-micrometer}를 찾아 자동 바인딩).
 * 예를 들어 {@code hibernate.statements}는 준비된 SQL 문 수, {@code hibernate.transactions}는 트랜잭션 성공·실패 수다.
 * {@code @Version} 충돌 횟수는 {@code hibernate.optimistic.failures}가 아니라 {@code jpa.optimistic.lock.failures}를 본다
 * (앞의 것은 충돌이 나도 오르지 않는다. {@link OptimisticLockCountingJpaDialect} 참고).
 *
 * <p>{@code app.metrics.jpa.enabled=false}로 이 설정 전체를 끈다. Hibernate 통계는 쿼리마다 통계를 쌓아 오버헤드가 있으므로,
 * 부하테스트로 운영 영향을 확인하기 전이나 문제가 생겼을 때 코드 수정 없이 끌 수 있게 한다. 끄면 {@code hibernate.*} 지표는 0으로 나온다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.metrics.jpa", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JpaMetricsConfig {

    @Bean
    public HibernatePropertiesCustomizer jpaMetricsHibernatePropertiesCustomizer() {
        return properties -> {
            properties.put(AvailableSettings.GENERATE_STATISTICS, true);
            properties.put(AvailableSettings.STATEMENT_INSPECTOR, new QueryCountStatementInspector());
        };
    }

    /**
     * EntityManagerFactory가 쓸 JPA 방언을 충돌을 세는 것으로 바꾼다. 트랜잭션 매니저도 이 방언을 이어받아 커밋 때 나는 충돌을 센다.
     * 방언은 EntityManagerFactory가 초기화되기 전에 정해져야 해서 초기화 전 단계에 끼운다.
     * 누군가 이미 방언을 지정했다면 그쪽을 존중해 덮어쓰지 않는다(이 경우 충돌 횟수는 세지 못하므로 지정 쪽에서 맞춰야 한다).
     */
    @Bean
    static BeanPostProcessor optimisticLockCountingDialectInstaller(ObjectProvider<MeterRegistry> registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LocalContainerEntityManagerFactoryBean factoryBean && factoryBean.getJpaDialect() == null) {
                    factoryBean.setJpaDialect(new OptimisticLockCountingJpaDialect(registry));
                }
                return bean;
            }
        };
    }

    @Bean
    public FilterRegistrationBean<QueryCountFilter> queryCountFilterRegistration(MeterRegistry registry) {
        // 순서는 기본값(가장 뒤). 인증 필터가 실행하는 쿼리는 세지 않고 컨트롤러 이후의 쿼리만 센다
        return new FilterRegistrationBean<>(new QueryCountFilter(registry));
    }
}
