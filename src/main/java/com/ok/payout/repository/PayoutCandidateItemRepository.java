package com.ok.payout.repository;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import org.springframework.data.repository.Repository;

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

    // 배송비 후보 중복 확인용. 배송비는 (주문, 판매자)당 1건 (평소 이벤트만 보려면 adjustmentId = 0)
    boolean existsByOrderIdAndSellerIdAndEventTypeAndAdjustmentId(Long orderId, Long sellerId, PayoutEventType eventType,
                                                                  Long adjustmentId);

    List<PayoutCandidateItem> saveAll(Iterable<PayoutCandidateItem> items);

    // 결과 검증 실패 기록을 만들 후보들 (#290). id 순으로 받아 실패 기록 순서가 매번 같게 함
    List<PayoutCandidateItem> findAllByIdInOrderByIdAsc(Collection<Long> ids);
}