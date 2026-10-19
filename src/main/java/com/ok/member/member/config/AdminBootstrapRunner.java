package com.ok.member.member.config;

import com.ok.member.member.service.AdminAccountService;
import com.ok.member.member.service.dto.AdminAccountCreation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 앱이 시작되면 설정값으로 최초 관리자 계정을 만든다.
 *
 * <p>외부 입력(설정값)이 들어오는 진입점이라 리스너처럼 서비스만 호출하고, 저장은 서비스에 맡긴다.
 * 로그에는 결과와 아이디만 남기고 비밀번호는 남기지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminAccountService adminAccountService;
    private final AdminBootstrapProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        AdminAccountCreation result = adminAccountService.createAdminIfAbsent(properties.toCommand());
        String loginId = properties.loginId();
        switch (result) {
            case CREATED -> log.info("최초 관리자 계정을 만들었습니다. loginId={}", loginId);
            case ALREADY_EXISTS -> log.info("관리자 계정이 이미 있어 만들지 않았습니다. 비밀번호도 바꾸지 않습니다. loginId={}", loginId);
            // 관리자 없이 서버가 뜨므로 원인을 찾을 수 있게 경고로 남긴다
            case LOGIN_ID_TAKEN -> log.warn("같은 아이디를 일반 회원이 쓰고 있어 관리자 계정을 만들지 않았습니다. "
                    + "ADMIN_BOOTSTRAP_LOGIN_ID를 바꿔 주세요. loginId={}", loginId);
        }
    }
}
