package com.ok.carriersimulator.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 관리 화면에 공통으로 들어가는 값(메뉴, 새로고침 간격)을 넣는다.
 */
@ControllerAdvice(basePackageClasses = AdminModelAdvice.class)
class AdminModelAdvice {

    private static final List<String[]> MENU = List.of(
            new String[] {"현황", "/"},
            new String[] {"송장", "/parcels"},
            new String[] {"보내기", "/send"},
            new String[] {"실험 설정", "/settings"});

    @ModelAttribute("refresh")
    int refresh(HttpServletRequest request) {
        return AdminSupport.refreshSeconds(request.getParameter("refresh"));
    }

    @ModelAttribute("navItems")
    List<NavItem> navItems(HttpServletRequest request) {
        int refresh = AdminSupport.refreshSeconds(request.getParameter("refresh"));
        String suffix = refresh > 0 ? "?refresh=" + refresh : "";
        String path = request.getRequestURI();
        List<NavItem> items = new ArrayList<>();
        for (String[] menu : MENU) {
            boolean active = menu[1].equals("/") ? path.equals("/") : path.startsWith(menu[1]);
            items.add(new NavItem(menu[0], menu[1] + suffix, active));
        }
        return items;
    }

    // 지금 화면의 다른 조건(거르기, 쪽 번호)은 그대로 두고 새로고침 간격만 바꾸는 링크
    @ModelAttribute("refreshLinks")
    List<NavItem> refreshLinks(HttpServletRequest request) {
        int current = AdminSupport.refreshSeconds(request.getParameter("refresh"));
        StringBuilder others = new StringBuilder();
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            if (parameter.getKey().equals("refresh")) {
                continue;
            }
            for (String value : parameter.getValue()) {
                others.append(others.isEmpty() ? "?" : "&")
                        .append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        List<NavItem> links = new ArrayList<>();
        for (int option : AdminSupport.REFRESH_OPTIONS) {
            String query = others.toString();
            if (option > 0) {
                query += (query.isEmpty() ? "?" : "&") + "refresh=" + option;
            }
            links.add(new NavItem(option == 0 ? "끔" : option + "초", request.getRequestURI() + query, option == current));
        }
        return links;
    }

    record NavItem(String label, String href, boolean active) {
    }
}
