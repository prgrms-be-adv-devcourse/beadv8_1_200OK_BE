package com.ok.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 로그인한 회원의 id를 넣는다. 값은 검증된 Access Token의 subject에서 꺼낸다.
 * 요청 헤더 등 클라이언트가 바꿀 수 있는 값으로 회원을 식별하지 않기 위해 쓴다.
 *
 * <pre>
 * &#64;GetMapping
 * public ResponseEntity&lt;CartResponse&gt; getMyCart(&#64;CurrentMemberId Long memberId) { ... }
 * </pre>
 *
 * <p>토큰이 없는 요청이면 401로 응답한다. 파라미터 타입은 {@code Long} 또는 {@code long}이어야 하며,
 * 그 밖의 타입에 붙이면 리졸버가 처리하지 않는다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentMemberId {
}
