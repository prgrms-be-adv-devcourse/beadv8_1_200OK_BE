package com.ok.common.exception;

import java.net.URI;
import java.util.List;

import com.ok.common.security.TokenValidationErrors;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
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
    // RFC 6750: 401 응답의 WWW-Authenticate 헤더로 인증 방식과 실패 원인을 알린다. 헤더 값은 ASCII여야 한다.
    private static final String BEARER = "Bearer";
    private static final String BEARER_INVALID_TOKEN = "Bearer error=\"invalid_token\"";
    private static final String BEARER_TOKEN_EXPIRED =
            "Bearer error=\"invalid_token\", error_description=\"The access token expired\"";

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    private final String typePrefix;

    public ApiControllerAdvice(@Value("${app.problem.base-uri}") String baseUri) {
        this.typePrefix = normalizePrefix(baseUri);
    }

    @ExceptionHandler(RestApiException.class)
    public ResponseEntity<ProblemDetail> handleRestApiException(RestApiException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity.status(errorCode.status()).body(toProblemDetail(errorCode, ex.getMessage()));
    }

    /**
     * 인증 실패를 401로 응답한다. 클라이언트가 다음 행동을 정할 수 있도록 원인을 구분한다.
     * <ul>
     *   <li>{@code token-expired}: 토큰 만료 → 토큰 재발급</li>
     *   <li>{@code invalid-token}: 위조·형식 오류 → 토큰을 버리고 재로그인</li>
     *   <li>{@code unauthorized}: 토큰 없음 → 로그인</li>
     * </ul>
     * 보안 필터에서 난 예외도 {@code ProblemDetailAuthenticationEntryPoint}가 이 핸들러로 넘겨, 응답 형식이 같다.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(AuthenticationException ex) {
        if (isExpiredToken(ex)) {
            return unauthorized(CommonErrorCode.TOKEN_EXPIRED, BEARER_TOKEN_EXPIRED);
        }
        // 토큰을 보냈지만 검증에 실패한 경우 (서명 불일치, 형식 오류, 발급자 불일치, 잘못된 회원 식별자)
        if (ex instanceof OAuth2AuthenticationException || ex instanceof BadCredentialsException) {
            return unauthorized(CommonErrorCode.INVALID_TOKEN, BEARER_INVALID_TOKEN);
        }
        return unauthorized();
    }

    /**
     * 인가 실패를 403으로 응답한다.
     * 단, 로그인하지 않은 요청이 {@code @PreAuthorize}에 걸려도 이 예외가 나므로, 그때는 권한 부족이 아니라 인증 필요(401)로 응답한다.
     * 이 핸들러가 없으면 아래 {@link #handleUnexpectedException}이 잡아 500이 된다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(AccessDeniedException ex) {
        if (isAnonymous()) {
            return unauthorized();
        }
        ErrorCode errorCode = CommonErrorCode.FORBIDDEN;
        return ResponseEntity.status(errorCode.status()).body(toProblemDetail(errorCode, errorCode.getMessage()));
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

    private ResponseEntity<ProblemDetail> unauthorized() {
        return unauthorized(CommonErrorCode.UNAUTHORIZED, BEARER);
    }

    private ResponseEntity<ProblemDetail> unauthorized(ErrorCode errorCode, String wwwAuthenticate) {
        return ResponseEntity.status(errorCode.status())
                .header(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate)
                .body(toProblemDetail(errorCode, errorCode.getMessage()));
    }

    /**
     * 토큰 검증 단계에서 만료 오류 코드를 남겼는지 확인한다. 검증 예외는 인증 예외의 원인(cause)으로 감싸져 전달된다.
     */
    private boolean isExpiredToken(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validationException) {
                return validationException.getErrors().stream()
                        .anyMatch(error -> TokenValidationErrors.EXPIRED.equals(error.getErrorCode()));
            }
        }
        return false;
    }

    private boolean isAnonymous() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || trustResolver.isAnonymous(authentication);
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
