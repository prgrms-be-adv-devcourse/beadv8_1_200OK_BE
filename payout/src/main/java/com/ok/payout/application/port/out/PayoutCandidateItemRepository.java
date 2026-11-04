package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 정산후보 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutCandidateItemRepository {

    // 구매 확정 중복 확인용 (평소 이벤트만 보려면 adjustmentId = 0)
    boolean existsByOrderItemIdAndEventTypeInAndAdjustmentId(Long orderItemId, Collection<PayoutEventType> eventTypes,
                                                             Long adjustmentId);

    // 배송비 후보를 "이미 있으면 그냥 넘어가기"로 저장한다. 배송비는 판매자 묶음 단위라 (주문, 판매자)당 1건 (#309)
    // 겹치면 아무것도 바꾸지 않고 성공으로 끝난다. 쿼리 설명은 어댑터의 해당 메서드에 있다
    void insertShippingFeeIfAbsent(String eventType, Long orderId, Long sellerId, Long amount, LocalDateTime now);

    <S extends PayoutCandidateItem> List<S> saveAll(Iterable<S> items);

    // 결과 검증 실패 기록을 만들 후보들 (#290). id 순으로 받아 실패 기록 순서가 매번 같게 함
    List<PayoutCandidateItem> findAllByIdInOrderByIdAsc(Collection<Long> ids);
}
