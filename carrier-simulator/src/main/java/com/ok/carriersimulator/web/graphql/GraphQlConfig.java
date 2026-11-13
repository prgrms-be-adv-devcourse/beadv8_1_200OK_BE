package com.ok.carriersimulator.web.graphql;

import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLScalarType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.http.HttpHeaders;

@Configuration
class GraphQlConfig {

    static final String AUTHORIZATION_CONTEXT_KEY = "authorization";

    // Delivery Tracker의 DateTime은 시간대를 포함한 RFC3339 문자열이다
    @Bean
    RuntimeWiringConfigurer dateTimeScalar() {
        GraphQLScalarType dateTime = GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("시간대를 포함한 RFC3339 시각")
                .coercing(new DateTimeCoercing())
                .build();
        return wiring -> wiring.scalar(dateTime);
    }

    // 토큰 확인은 컨트롤러가 core에 물어서 한다. 여기서는 헤더 값만 실행 컨텍스트로 넘긴다.
    // 헤더가 없거나 틀려도 HTTP 401이 아니라 200 본문의 UNAUTHENTICATED로 답해야 하기 때문이다
    @Bean
    WebGraphQlInterceptor authorizationInterceptor() {
        return (request, chain) -> {
            String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            request.configureExecutionInput((input, builder) ->
                    builder.graphQLContext(Map.of(AUTHORIZATION_CONTEXT_KEY, header == null ? "" : header)).build());
            return chain.next(request);
        };
    }

    @Bean
    DataFetcherExceptionResolverAdapter simulatorErrorResolver() {
        return new DataFetcherExceptionResolverAdapter() {
            @Override
            protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
                if (!(ex instanceof SimulatorGraphQlException simulatorException)) {
                    return null;
                }
                return GraphqlErrorBuilder.newError(env)
                        .message(simulatorException.getMessage())
                        .errorType(ErrorType.BAD_REQUEST)
                        .extensions(Map.of("code", simulatorException.code().name()))
                        .build();
            }
        };
    }

    private static final class DateTimeCoercing implements Coercing<OffsetDateTime, String> {

        @Override
        public String serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale) {
            if (dataFetcherResult instanceof Instant instant) {
                return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
            }
            if (dataFetcherResult instanceof OffsetDateTime dateTime) {
                return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
            }
            throw new CoercingSerializeException("DateTime으로 바꿀 수 없는 값입니다.");
        }

        @Override
        public OffsetDateTime parseValue(Object input, GraphQLContext graphQLContext, Locale locale) {
            if (input instanceof String text) {
                try {
                    return OffsetDateTime.parse(text);
                } catch (DateTimeParseException e) {
                    throw new CoercingParseValueException("DateTime 형식이 아닙니다: " + text);
                }
            }
            throw new CoercingParseValueException("DateTime은 문자열이어야 합니다.");
        }

        @Override
        public OffsetDateTime parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext graphQLContext,
                                           Locale locale) {
            if (input instanceof StringValue text) {
                try {
                    return OffsetDateTime.parse(text.getValue());
                } catch (DateTimeParseException e) {
                    throw new CoercingParseLiteralException("DateTime 형식이 아닙니다: " + text.getValue());
                }
            }
            throw new CoercingParseLiteralException("DateTime은 문자열이어야 합니다.");
        }
    }
}
