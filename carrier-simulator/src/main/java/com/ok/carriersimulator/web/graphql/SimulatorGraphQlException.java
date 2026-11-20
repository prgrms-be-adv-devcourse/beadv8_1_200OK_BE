package com.ok.carriersimulator.web.graphql;

/**
 * GraphQL 응답의 {@code errors[].extensions.code}로 나갈 오류다. 값은 Delivery Tracker의 ErrorCode와 같다.
 */
public class SimulatorGraphQlException extends RuntimeException {

    private final Code code;

    public SimulatorGraphQlException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        UNAUTHENTICATED,
        FORBIDDEN,
        INTERNAL,
        BAD_REQUEST,
        NOT_FOUND
    }
}
