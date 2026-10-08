package com.ok.common.entity;

import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
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