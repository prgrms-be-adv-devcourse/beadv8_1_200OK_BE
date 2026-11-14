package com.ok.carriersimulator.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.SourceLocation;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GraphQlConfigTest {

    @ParameterizedTest
    @MethodSource("오류_분류와_코드")
    void 코드가_없는_오류에는_분류에_맞는_코드를_붙인다(ErrorClassification classification, String expectedCode) {
        // given
        GraphQLError error = GraphqlErrorBuilder.newError()
                .message("원래 메시지")
                .errorType(classification)
                .location(new SourceLocation(1, 9))
                .path(List.of("track", "events"))
                .build();

        // when
        GraphQLError sut = GraphQlConfig.withCode(error);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getExtensions()).containsEntry("code", expectedCode);
            softly.assertThat(sut.getMessage()).isEqualTo("원래 메시지");
            softly.assertThat(sut.getErrorType()).isEqualTo(classification);
            softly.assertThat(sut.getLocations()).extracting(SourceLocation::getColumn).containsExactly(9);
            softly.assertThat(sut.getPath()).containsExactly("track", "events");
        });
    }

    static Stream<Arguments> 오류_분류와_코드() {
        return Stream.of(
                Arguments.of(ErrorType.ValidationError, "BAD_REQUEST"),
                Arguments.of(ErrorType.InvalidSyntax, "BAD_REQUEST"),
                Arguments.of(org.springframework.graphql.execution.ErrorType.BAD_REQUEST, "BAD_REQUEST"),
                Arguments.of(org.springframework.graphql.execution.ErrorType.INTERNAL_ERROR, "INTERNAL"),
                Arguments.of(ErrorType.DataFetchingException, "INTERNAL"));
    }

    @Test
    void 이미_코드가_있는_오류는_그대로_둔다() {
        // given
        GraphQLError error = GraphqlErrorBuilder.newError()
                .message("송장을 찾을 수 없습니다.")
                .errorType(org.springframework.graphql.execution.ErrorType.BAD_REQUEST)
                .extensions(Map.of("code", "NOT_FOUND"))
                .build();

        // when
        GraphQLError sut = GraphQlConfig.withCode(error);

        // then
        assertThat(sut).isSameAs(error);
    }
}
