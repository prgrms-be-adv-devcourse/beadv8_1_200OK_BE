package com.ok.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentMemberId}가 붙은 파라미터에 토큰의 회원 id를 넣는다.
 *
 * <p>인증에 실패하면 {@code AuthenticationException}을 던지고, 공통 예외 처리에서 401로 응답한다.
 * 컨트롤러가 회원 id를 받는 순간 인증 여부가 확정되므로, 회원 id가 필요한 API는 별도 설정 없이 로그인이 필요해진다.
 */
public class CurrentMemberIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(CurrentMemberId.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        if (!Long.class.equals(type) && !long.class.equals(type)) {
            // 다른 타입에 붙이면 이 리졸버를 건너뛰고 기본 바인딩이 null을 넣는다. 조용히 넘어가지 않고 바로 알린다.
            throw new IllegalStateException("@CurrentMemberId는 Long 또는 long 파라미터에만 쓸 수 있습니다: " + parameter);
        }
        return true;
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 토큰 없이 들어온 요청은 익명 인증이거나 인증 정보가 없다. 둘 다 회원을 식별할 수 없다.
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }
        // subject는 우리가 발급할 때 넣는 회원 id(양수)다. 발급 쪽에서만 만들므로 값의 범위는 검사하지 않고 형식만 확인한다.
        String subject = jwtAuthentication.getToken().getSubject();
        if (subject == null) {
            throw new BadCredentialsException("토큰에 회원 식별자가 없습니다.");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            // 우리가 발급한 토큰의 subject는 항상 회원 id다. 숫자가 아니면 정상적으로 발급된 토큰이 아니다.
            throw new BadCredentialsException("토큰의 회원 식별자가 올바르지 않습니다.", e);
        }
    }

}
