package com.ok.common.jpa;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;

/**
 * JPA 예외를 Spring 예외로 바꾸는 지점에서 낙관적 락 충돌({@code @Version}) 횟수를 {@value #METRIC_NAME}로 센다.
 *
 * <p>Hibernate 통계의 {@code hibernate.optimistic.failures}는 이 프로젝트의 충돌(UPDATE 0행, 커밋 때 버전 증가 실패)에서 오르지 않는 것을
 * 통합 테스트로 확인해 쓰지 않는다. 이 클래스는 repository 호출과 트랜잭션 커밋 양쪽의 예외 번역이 거치는 곳이라 충돌 경로와 상관없이 한 번씩 센다.
 * 서비스가 예외를 잡아 처리해도 이미 센 뒤다. 엔티티 이름만 태그로 달고, 충돌한 행의 id는 값이 무한히 늘 수 있어 달지 않는다.
 */
public class OptimisticLockCountingJpaDialect extends HibernateJpaDialect {

    static final String METRIC_NAME = "jpa.optimistic.lock.failures";

    private final ObjectProvider<MeterRegistry> registry;

    // 이 객체는 EntityManagerFactory를 만들 때 쓰이는데 그 시점에는 MeterRegistry가 아직 없을 수 있어 처음 셀 때 찾는다
    public OptimisticLockCountingJpaDialect(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
        DataAccessException translated = super.translateExceptionIfPossible(ex);
        if (translated instanceof OptimisticLockingFailureException failure) {
            count(failure);
        }
        return translated;
    }

    private void count(OptimisticLockingFailureException failure) {
        MeterRegistry meterRegistry = registry.getIfAvailable();
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_NAME)
                .description("낙관적 락(@Version) 충돌 횟수")
                .tag("entity", entityName(failure))
                .register(meterRegistry)
                .increment();
    }

    private static String entityName(OptimisticLockingFailureException failure) {
        if (failure instanceof ObjectOptimisticLockingFailureException objectFailure) {
            String className = objectFailure.getPersistentClassName();
            if (className != null) {
                return className.substring(className.lastIndexOf('.') + 1);
            }
        }
        return "unknown";
    }
}
