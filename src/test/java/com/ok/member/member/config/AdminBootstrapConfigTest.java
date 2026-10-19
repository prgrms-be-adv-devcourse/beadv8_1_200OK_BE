package com.ok.member.member.config;

import com.ok.member.member.service.AdminAccountService;
import com.ok.member.member.service.dto.AdminAccountCommand;
import com.ok.member.member.service.dto.AdminAccountCreation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

// 전체 앱을 띄우지 않고 이 설정만 올려, 설정값에 따라 빈이 등록되는지와 앱 시작이 막히는지 확인한다
class AdminBootstrapConfigTest {

    private final AdminAccountService adminAccountService = mock(AdminAccountService.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AdminBootstrapConfig.class)
            .withBean(AdminAccountService.class, () -> adminAccountService);

    @Test
    void 관리자_아이디가_없으면_최초_관리자_생성을_등록하지_않는다() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AdminBootstrapRunner.class)
                .doesNotHaveBean(AdminBootstrapProperties.class));
    }

    @Test
    void 관리자_아이디를_비워_두면_비밀번호와_이메일이_없어도_앱이_그대로_시작된다() {
        // .env.example처럼 ADMIN_BOOTSTRAP_LOGIN_ID= 로 비워 둔 경우
        contextRunner.withPropertyValues("admin-bootstrap.login-id=", "admin-bootstrap.password=")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(AdminBootstrapRunner.class));
    }

    @Test
    void 관리자_설정값이_모두_있으면_최초_관리자_생성을_등록한다() {
        contextRunner.withPropertyValues(
                        "admin-bootstrap.login-id=admin01",
                        "admin-bootstrap.password=Admin123!",
                        "admin-bootstrap.email=admin@example.com")
                .run(context -> assertThat(context).hasSingleBean(AdminBootstrapRunner.class));
    }

    @Test
    void 관리자_아이디만_넣고_비밀번호를_빠뜨리면_앱_시작이_막힌다() {
        contextRunner.withPropertyValues(
                        "admin-bootstrap.login-id=admin01",
                        "admin-bootstrap.email=admin@example.com")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("ADMIN_BOOTSTRAP_PASSWORD"));
    }

    @Test
    void 앱이_시작되면_정규화한_설정값으로_관리자_생성을_요청한다() {
        // given
        given(adminAccountService.createAdminIfAbsent(
                new AdminAccountCommand("admin01", "Admin123!", "admin@example.com")))
                .willReturn(AdminAccountCreation.CREATED);

        contextRunner.withPropertyValues(
                        "admin-bootstrap.login-id= Admin01 ",
                        "admin-bootstrap.password=Admin123!",
                        "admin-bootstrap.email=Admin@Example.com")
                .run(context -> {
                    // when: ApplicationContextRunner는 ApplicationRunner를 실행하지 않아 직접 실행한다
                    context.getBean(AdminBootstrapRunner.class).run(new DefaultApplicationArguments());

                    // then
                    then(adminAccountService).should().createAdminIfAbsent(
                            new AdminAccountCommand("admin01", "Admin123!", "admin@example.com"));
                });
    }

}
