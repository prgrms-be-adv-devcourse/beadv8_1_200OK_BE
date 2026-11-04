package com.ok.payout.application.port.out;

import com.ok.payout.PayoutSchema;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PayoutCandidateItemRepository extends Repository<PayoutCandidateItem, Long> {

    boolean existsByOrderItemIdAndEventType(Long orderItemId, PayoutEventType eventType);

    PayoutCandidateItem save(PayoutCandidateItem item);

    Optional<PayoutCandidateItem> findById(Long id);

    List<PayoutCandidateItem> findAllByOrderItemId(Long orderItemId);

    // 한 주문의 정산후보 전체. 배송비 행은 orderItemId가 null이라 findAllByOrderItemId로는 찾을 수 없음
    List<PayoutCandidateItem> findAllByOrderId(Long orderId);

    // 구매 확정 중복 확인용 (평소 이벤트만 보려면 adjustmentId = 0)
    boolean existsByOrderItemIdAndEventTypeInAndAdjustmentId(Long orderItemId, Collection<PayoutEventType> eventTypes,
                                                             Long adjustmentId);

    // 배송비 후보를 "이미 있으면 그냥 넘어가기"로 저장한다. 배송비는 판매자 묶음 단위라 (주문, 판매자)당 1건 (#309)
    // 확인 → 저장으로 나누면 같은 묶음이 동시에 처리될 때 둘 다 "없음"으로 보고, 늦은 쪽이 유니크 키 예외로
    // 같은 트랜잭션의 대금·수수료까지 롤백됐다. 한 문장으로 넣어 겹치면 아무것도 바꾸지 않고(id = id) 성공으로 끝낸다
    // 겹침 판단은 기존 유니크 키 (order_id, ship_seller_id, adjustment_id)가 한다. 평소 이벤트라 adjustment_id = 0
    // 직접 쓴 SQL이라 JPA 자동 시각이 채워지지 않아 시각을 받는다. monolith는 기본 DB가 달라 스키마 이름을 붙인다
    // event_type은 enum 이름을 받고 adjustment_id는 상수를 이어 붙여, 이름이 바뀌면 SQL 글자가 어긋나지 않고 컴파일 오류가 나게 한다
    // 엔티티에 NOT NULL 컬럼이 추가되면 이 SQL에도 넣어야 한다 (JPA 저장이 아니라 자동으로 따라가지 않음)
    @Modifying
    @Query(value = "INSERT INTO " + PayoutSchema.NAME + ".payout_candidate_items "
            + "(event_type, order_id, order_item_id, seller_id, amount, adjustment_id, created_at, updated_at) "
            + "VALUES (:eventType, :orderId, NULL, :sellerId, :amount, "
            + PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID + ", :now, :now) "
            + "ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    void insertShippingFeeIfAbsent(String eventType, Long orderId, Long sellerId, Long amount, LocalDateTime now);

    List<PayoutCandidateItem> saveAll(Iterable<PayoutCandidateItem> items);

    // 결과 검증 실패 기록을 만들 후보들 (#290). id 순으로 받아 실패 기록 순서가 매번 같게 함
    List<PayoutCandidateItem> findAllByIdInOrderByIdAsc(Collection<Long> ids);
}
