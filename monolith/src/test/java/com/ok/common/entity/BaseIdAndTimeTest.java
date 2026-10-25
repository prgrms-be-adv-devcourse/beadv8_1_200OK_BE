package com.ok.common.entity;

import com.ok.PaldoGotganApplication;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import com.ok.common.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = PaldoGotganApplication.class)
// @DataJpaTest는 모듈별 Flyway 설정(common·모듈 스키마 인스턴스)을 읽지 않고 Boot의 Flyway 하나만 쓴다.
// 이 테스트는 기본 스키마의 common 마이그레이션(테스트용 테이블 포함)만 필요하므로 위치를 좁히고,
// 다른 모듈 스키마의 테이블은 만들지 않으므로 전체 엔티티의 스키마 검증(validate)은 끈다. 모듈 엔티티는 각 모듈의 테스트가 검증한다
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration/common",
        "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class BaseIdAndTimeTest {
    @Autowired
    TestEntityManager em;

    @Test
    void 저장하면_id와_생성_수정_시각이_채워진다() {
        TestAuditEntity entity = em.persistAndFlush(new TestAuditEntity("before"));

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void 수정하면_수정_시각만_갱신된다() throws InterruptedException {
        TestAuditEntity entity = em.persistAndFlush(new TestAuditEntity("before"));
        LocalDateTime createdAt = entity.getCreatedAt();
        LocalDateTime updatedAt = entity.getUpdatedAt();

        Thread.sleep(10);
        entity.changeName("after");
        em.flush();

        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isAfter(updatedAt);
    }
}