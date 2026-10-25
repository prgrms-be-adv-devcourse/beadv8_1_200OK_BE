package com.ok.common.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String name();

    HttpStatus status();

    String getTitle();

    String getMessage();

    default String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
