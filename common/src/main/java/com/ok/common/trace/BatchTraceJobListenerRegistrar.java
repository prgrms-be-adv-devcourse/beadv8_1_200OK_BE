package com.ok.common.trace;

import org.springframework.batch.core.job.AbstractJob;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 모든 Batch Job에 {@link BatchTraceJobListener}를 붙인다. Job 설정 코드를 바꾸지 않고 실행마다 새 traceId를 두기 위해서다.
 *
 * <p>BeanPostProcessor는 일반 빈보다 먼저 만들어지므로 발급기는 Job을 처음 만날 때 꺼낸다.
 */
class BatchTraceJobListenerRegistrar implements BeanPostProcessor {

    private final ObjectProvider<TraceIdGenerator> generator;
    private BatchTraceJobListener listener;

    BatchTraceJobListenerRegistrar(ObjectProvider<TraceIdGenerator> generator) {
        this.generator = generator;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof AbstractJob job) {
            if (listener == null) {
                listener = new BatchTraceJobListener(generator.getObject());
            }
            job.registerJobExecutionListener(listener);
        }
        return bean;
    }
}
