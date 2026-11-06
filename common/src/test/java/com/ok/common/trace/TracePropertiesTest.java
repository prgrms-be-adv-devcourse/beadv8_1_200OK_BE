package com.ok.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TracePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableTraceProperties.class);

    @EnableConfigurationProperties(TraceProperties.class)
    static class EnableTraceProperties {
    }

    @Test
    void 설정이_없으면_노드_번호는_0이다() {
        // when & then
        runner.run(context -> assertThat(context.getBean(TraceProperties.class).nodeId()).isZero());
    }

    @Test
    void trace_node_id_설정을_읽는다() {
        // when & then
        runner.withPropertyValues("trace.node-id=7")
                .run(context -> assertThat(context.getBean(TraceProperties.class).nodeId()).isEqualTo(7));
    }

    @ParameterizedTest
    @MethodSource("범위를_벗어난_노드_번호")
    void 노드_번호가_범위를_벗어나면_기동이_실패한다(String nodeId) {
        // when & then
        runner.withPropertyValues("trace.node-id=" + nodeId)
                .run(context -> assertThat(context).hasFailed());
    }

    static Stream<String> 범위를_벗어난_노드_번호() {
        return Stream.of("-1", "1024");
    }

    @Test
    void 생성자는_범위를_벗어난_값을_거부한다() {
        // when & then
        assertThatThrownBy(() -> new TraceProperties(1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("trace.node-id는 0 이상 1023 이하여야 합니다. 입력값: 1024");
    }
}
