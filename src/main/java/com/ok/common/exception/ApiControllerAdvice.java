package com.ok.common.exception;

import java.net.URI;
import java.util.List;

import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class ApiControllerAdvice extends ResponseEntityExceptionHandler {

    private static final String BLANK_TYPE = "about:blank";
    private static final String ERRORS_PROPERTY = "errors";

    private final String typePrefix;

    public ApiControllerAdvice(@Value("${app.problem.base-uri}") String baseUri) {
        this.typePrefix = normalizePrefix(baseUri);
    }

    @ExceptionHandler(RestApiException.class)
    public ResponseEntity<ProblemDetail> handleRestApiException(RestApiException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity.status(errorCode.status()).body(toProblemDetail(errorCode, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception ex) {
        log.error("처리되지 않은 예외가 발생했습니다.", ex);
        ErrorCode errorCode = CommonErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(errorCode.status()).body(toProblemDetail(errorCode, errorCode.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorDetail> errors = ex.getBindingResult().getAllErrors().stream()
                .map(this::toFieldErrorDetail)
                .toList();
        ErrorCode errorCode = CommonErrorCode.VALIDATION_FAILED;
        ProblemDetail problemDetail = toProblemDetail(errorCode, errorCode.getMessage());
        problemDetail.setProperty(ERRORS_PROPERTY, errors);
        return ResponseEntity.status(errorCode.status()).body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return toResponse(CommonErrorCode.INVALID_QUERY_PARAMETER);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return toResponse(CommonErrorCode.INVALID_JSON);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail
                && statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            applyType(problemDetail, CommonErrorCode.BAD_REQUEST);
        }
        return response;
    }

    private ResponseEntity<Object> toResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(toProblemDetail(errorCode, errorCode.getMessage()));
    }

    private ProblemDetail toProblemDetail(ErrorCode errorCode, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        applyType(problemDetail, errorCode);
        return problemDetail;
    }

    private void applyType(ProblemDetail problemDetail, ErrorCode errorCode) {
        if (BLANK_TYPE.equals(typePrefix)) {
            problemDetail.setType(URI.create(BLANK_TYPE));
            problemDetail.setTitle(errorCode.status().getReasonPhrase());
            return;
        }
        problemDetail.setType(URI.create(typePrefix + errorCode.slug()));
        problemDetail.setTitle(errorCode.getTitle());
    }

    private FieldErrorDetail toFieldErrorDetail(ObjectError error) {
        if (error instanceof FieldError fieldError) {
            return new FieldErrorDetail(error.getDefaultMessage(), toPointer(fieldError.getField()), fieldError.getField());
        }
        return new FieldErrorDetail(error.getDefaultMessage(), null, error.getObjectName());
    }

    private String toPointer(String path) {
        return "/" + path.replace("[", "/").replace("]", "").replace(".", "/");
    }

    private static String normalizePrefix(String baseUri) {
        if (baseUri == null || baseUri.isBlank() || BLANK_TYPE.equals(baseUri.trim())) {
            return BLANK_TYPE;
        }
        String prefix = baseUri.trim();
        return prefix.endsWith("/") || prefix.endsWith(":") ? prefix : prefix + "/";
    }
}
