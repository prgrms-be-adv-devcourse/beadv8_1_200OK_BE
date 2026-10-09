package com.ok.payout.repository;

import com.ok.config.JpaConfig;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 슬라이스 테스트: 실제 MySQL 컨테이너 + Flyway 마이그레이션으로 만든 테이블에서 검증
// @DataJpaTest는 테스트마다 자동 롤백되므로, 같은 주문항목 ID(1L)를 써도 테스트끼리 섞이지 않는다
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class PayoutCandidateItemRepositoryTest {

    @Autowired
    private PayoutCandidateItemRepository repository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("저장하면 id와 생성/수정 시각이 채워진다")
    void fillsIdAndTimestamps_whenSaved() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        PayoutCandidateItem item = PayoutCandidateItem.ofSaleAmount(orderItemId, 10L, 9_700L);

        // Act
        Long id = repository.save(item).getId();
        em.flush();   // DB에 INSERT 반영
        em.clear();   // 영속성 컨텍스트 비우기 → 다음 조회는 DB에서 읽음

        // Assert
        PayoutCandidateItem found = repository.findById(id).orElseThrow();
        assertThat(found.getEventType()).isEqualTo(PayoutEventType.SALE_AMOUNT);
        assertThat(found.getOrderItemId()).isEqualTo(orderItemId);
        assertThat(found.getSellerId()).isEqualTo(10L);
        assertThat(found.getAmount()).isEqualTo(9_700L);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("저장된 정산후보는 값을 바꿔도 DB에 반영되지 않는다")
    void doesNotUpdateDb_whenSavedCandidateIsModified() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        Long id = repository.save(PayoutCandidateItem.ofSaleAmount(orderItemId, 10L, 9_700L)).getId();
        em.flush();
        em.clear();
        PayoutCandidateItem found = repository.findById(id).orElseThrow();

        // Act: 변경 메서드가 없으니 리플렉션으로 강제 변경 후 flush
        ReflectionTestUtils.setField(found, "amount", 1L);
        em.flush();
        em.clear();

        // Assert: @Immutable이라 UPDATE가 나가지 않아 원래 값 유지
        PayoutCandidateItem reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getAmount()).isEqualTo(9_700L);
    }

    @Test
    @DisplayName("주문항목과 이벤트타입으로 존재 여부를 조회한다")
    void checksExistenceByOrderItemIdAndEventType() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        Long otherOrderItemId = orderItemId + 1;   // 저장하지 않은 주문항목
        repository.save(PayoutCandidateItem.ofSaleAmount(orderItemId, 10L, 9_700L));
        em.flush();

        // Act & Assert
        assertThat(repository.existsByOrderItemIdAndEventType(orderItemId, PayoutEventType.SALE_AMOUNT)).isTrue();
        assertThat(repository.existsByOrderItemIdAndEventType(orderItemId, PayoutEventType.SALE_FEE)).isFalse();
        assertThat(repository.existsByOrderItemIdAndEventType(otherOrderItemId, PayoutEventType.SALE_AMOUNT)).isFalse();
    }

    @Test
    @DisplayName("같은 주문항목에 같은 이벤트타입은 두 번 저장할 수 없다")
    void cannotSaveSameEventTypeTwiceForSameOrderItem() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        repository.save(PayoutCandidateItem.ofSaleAmount(orderItemId, 10L, 9_700L));
        em.flush();

        // Act & Assert (DB 유니크 제약 uk_payout_candidate_items_order_item_id_event_type)
        // em.flush()는 리포지토리를 거치지 않아 예외가 Spring 예외로 바뀌지 않을 수 있으므로,
        // 예외 타입 대신 가장 밑의 원인이 MySQL 중복 키 예외인지 확인
        assertThatThrownBy(() -> {
            repository.save(PayoutCandidateItem.ofSaleAmount(orderItemId, 10L, 9_700L));
            em.flush();
        }).hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    @Test
    @DisplayName("같은 주문항목이라도 이벤트타입이 다르면 저장된다")
    void savesDifferentEventTypesForSameOrderItem() {
        // Arrange
        Long orderItemId = nextOrderItemId();

        // Act
        repository.save(PayoutCandidateItem.ofSaleAmount(orderItemId, 10L, 9_700L));
        repository.save(PayoutCandidateItem.ofSaleFee(orderItemId, 10L, 300L));
        em.flush();

        // Assert
        assertThat(repository.findAllByOrderItemId(orderItemId)).hasSize(2);
    }


    private Long nextOrderItemId() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT MAX(order_item_id) FROM payout_candidate_items",
                Long.class);
        return maxId == null ? 100L : maxId + 1;
    }
}