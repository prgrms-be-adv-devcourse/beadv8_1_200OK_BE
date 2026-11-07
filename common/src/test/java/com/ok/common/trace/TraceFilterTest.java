package com.ok.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.util.stream.Stream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 실제 서블릿 필터 체인(Spring Security 포함)에 {@link TraceFilter}를 등록해, 응답 헤더와 로그의 MDC가 같은 traceId인지,
 * 인증 실패(401)·접근 거부(403) 응답에도 헤더가 실리는지 확인한다.
 */
@WebMvcTest
@Import({TraceConfig.class, TraceFilterTest.WebFixture.class})
class TraceFilterTest {

    // common에는 애플리케이션 클래스가 없어 슬라이스가 찾을 설정을 직접 둔다
    @SpringBootConfiguration
    static class TestApplication {
    }

    // @Configuration을 붙이지 않는다. common의 테스트 클래스는 다른 모듈 통합 테스트의 컴포넌트 스캔에도 노출된다
    static class WebFixture {

        static final org.slf4j.Logger LOG = LoggerFactory.getLogger("trace-filter-test");

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .httpBasic(Customizer.withDefaults())
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/open").permitAll()
                            .requestMatchers("/admin").hasRole("ADMIN")
                            .anyRequest().authenticated())
                    .build();
        }

        @Bean
        RouterFunction<ServerResponse> testRoutes() {
            return RouterFunctions.route()
                    .GET("/open", request -> {
                        LOG.info("요청 처리");
                        return ServerResponse.ok().body("ok");
                    })
                    .GET("/secure", request -> {
                        LOG.info("요청 처리");
                        return ServerResponse.ok().body("ok");
                    })
                    .GET("/admin", request -> ServerResponse.ok().body("ok"))
                    .build();
        }
    }

    @Autowired
    MockMvc mockMvc;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                // MDC는 이벤트를 읽을 때 현재 스레드 값을 보는 지연 방식이라, 찍힌 시점의 값을 고정해 둔다
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger("trace-filter-test")).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger("trace-filter-test")).detachAppender(appender);
        MDC.clear();
    }

    @Test
    void 요청마다_응답_헤더에_traceId를_싣고_같은_요청의_로그_MDC와_같다() throws Exception {
        // when
        MvcResult sut = mockMvc.perform(get("/open")).andReturn();

        // then
        String header = sut.getResponse().getHeader("X-Trace-Id");
        assertThat(header).matches("[0-9]{1,19}");
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry("traceId", header);
    }

    @Test
    void 요청마다_다른_traceId를_발급한다() throws Exception {
        // when
        String first = mockMvc.perform(get("/open")).andReturn().getResponse().getHeader("X-Trace-Id");
        String second = mockMvc.perform(get("/open")).andReturn().getResponse().getHeader("X-Trace-Id");

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 유효한_X_Trace_Id_헤더는_이어받는다() throws Exception {
        // when
        MvcResult sut = mockMvc.perform(get("/open").header("X-Trace-Id", "123456789012345678")).andReturn();

        // then
        assertThat(sut.getResponse().getHeader("X-Trace-Id")).isEqualTo("123456789012345678");
        assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry("traceId", "123456789012345678");
    }

    @ParameterizedTest
    @MethodSource("유효하지_않은_헤더_값")
    void 유효하지_않은_X_Trace_Id_헤더는_무시하고_새로_발급한다(String incoming) throws Exception {
        // when
        MvcResult sut = mockMvc.perform(get("/open").header("X-Trace-Id", incoming)).andReturn();

        // then
        String header = sut.getResponse().getHeader("X-Trace-Id");
        assertThat(header).isNotEqualTo(incoming).matches("[0-9]{1,19}");
        assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry("traceId", header);
    }

    static Stream<String> 유효하지_않은_헤더_값() {
        return Stream.of("abc", "12ab", "1234567890123456789", "-1", "1 2", "12\n34", "<script>", "0x10", "0", "1", "12345678901234", "0123456789012345");
    }

    @Test
    void 인증하지_않은_요청의_401_응답에도_traceId_헤더가_있다() throws Exception {
        // when
        MvcResult sut = mockMvc.perform(get("/secure").accept(MediaType.APPLICATION_JSON)).andReturn();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getResponse().getStatus()).isEqualTo(401);
            softly.assertThat(sut.getResponse().getHeader("X-Trace-Id")).matches("[0-9]{1,19}");
        });
    }

    @Test
    void 권한이_없는_요청의_403_응답에도_traceId_헤더가_있다() throws Exception {
        // when
        MvcResult sut = mockMvc.perform(get("/admin").with(user("u").roles("USER"))).andReturn();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getResponse().getStatus()).isEqualTo(403);
            softly.assertThat(sut.getResponse().getHeader("X-Trace-Id")).matches("[0-9]{1,19}");
        });
    }

    @Test
    void 요청이_끝나면_MDC를_진입_전으로_되돌린다() throws Exception {
        // given
        MDC.put("before", "value");

        // when
        mockMvc.perform(get("/open"));

        // then
        assertThat(MDC.getCopyOfContextMap()).containsExactly(java.util.Map.entry("before", "value"));
    }
}
