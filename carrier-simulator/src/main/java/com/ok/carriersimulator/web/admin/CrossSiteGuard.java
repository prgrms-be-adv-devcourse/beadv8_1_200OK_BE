package com.ok.carriersimulator.web.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 다른 웹 사이트가 이 서버로 보낸 폼을 거절한다.
 *
 * <p>관리 화면에는 로그인이 없다. 그대로 두면 개발자가 브라우저로 연 아무 웹 페이지가
 * {@code http://127.0.0.1:8090/settings/clear-all} 같은 주소로 폼을 보내 송장을 지우거나,
 * 지정 전송으로 이 컴퓨터에서 임의의 주소로 요청을 보내게 할 수 있다.
 * 브라우저가 붙이는 출처 헤더를 보고 같은 주소에서 온 요청만 받는다.
 */
@Component
public class CrossSiteGuard extends OncePerRequestFilter {

    // delivery가 부르는 택배사 API는 화면이 아니라서 출처를 따지지 않는다
    private static final Set<String> API_PATHS = Set.of("/graphql", "/oauth2/token");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    // none은 주소창에 직접 넣는 것처럼 다른 페이지가 시킨 요청이 아닐 때 브라우저가 보내는 값이다
    private static final Set<String> SAME_SITE_VALUES = Set.of("same-origin", "none");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || API_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (fromAnotherSite(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("다른 사이트에서 보낸 요청은 받지 않습니다.");
            return;
        }
        chain.doFilter(request, response);
    }

    // 브라우저는 폼을 보낼 때 Sec-Fetch-Site나 Origin을 붙인다. 둘 다 없으면 curl 같은 도구로 보고 받는다
    private static boolean fromAnotherSite(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null) {
            return !SAME_SITE_VALUES.contains(fetchSite);
        }
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return !origin.equals(request.getScheme() + "://" + request.getHeader("Host"));
        }
        return false;
    }
}
