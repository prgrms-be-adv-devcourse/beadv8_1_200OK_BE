package com.ok.refund.adapter.out.persistence;

import com.ok.refund.domain.Refund;

/**
 * 도메인 {@link Refund}와 {@link RefundJpaEntity}를 서로 바꾼다. 영속 어댑터 안에서만 쓴다.
 * 칸이 늘면 도메인·엔티티·이 클래스 세 곳을 함께 고쳐야 한다(#481에서 감수하기로 한 비용).
 */
final class RefundMapper {

    private RefundMapper() {
    }

    static Refund toDomain(RefundJpaEntity entity) {
        return Refund.restore(
                entity.getId(),
                entity.getOrderId(),
                entity.getOrderItemId(),
                entity.getQuantity(),
                entity.getBuyerId(),
                entity.getSellerId(),
                entity.getProductOptionId(),
                entity.getProductName(),
                entity.getOptionName(),
                entity.getUnitPrice(),
                entity.getItemAmount(),
                entity.getShippingFeeAmount(),
                entity.getRemoteAreaFeeAmount(),
                entity.getDeductedShippingFeeAmount(),
                entity.getCancelReason(),
                entity.getStatus(),
                entity.getRejectReason(),
                entity.getDecidedAt(),
                entity.getCompletedAt(),
                entity.getFailedStep(),
                entity.getFailureCode(),
                entity.getActiveOrderItemId(),
                entity.getCreatedAt());
    }

    // 새 행. 요청 때 정해지는 칸을 넣고, 바뀌는 칸(상태·진행 중 칸·배송비 등)은 수정과 같은 applyMutableState로 옮긴다.
    // 같은 메서드를 써야 새 행과 수정이 옮기는 칸 목록이 어긋나지 않는다
    static RefundJpaEntity toNewEntity(Refund refund) {
        RefundJpaEntity entity = new RefundJpaEntity(
                refund.getOrderId(),
                refund.getOrderItemId(),
                refund.getQuantity(),
                refund.getBuyerId(),
                refund.getSellerId(),
                refund.getProductOptionId(),
                refund.getProductName(),
                refund.getOptionName(),
                refund.getUnitPrice(),
                refund.getItemAmount(),
                refund.getCancelReason());
        applyMutableState(refund, entity);
        return entity;
    }

    // 바뀔 수 있는 칸만 옮긴다: 상태, 거절 사유, 결정 시각, 완료 시각, 진행 중 칸, 배송비 세 칸, 실패 단계·이유.
    // 나머지 칸은 요청 때 정해져 바뀌지 않으므로 옮기지 않는다. 그래서 수정 때 이미 저장된 주문·금액 칸을 덮어쓸 일이 없다
    static void applyMutableState(Refund refund, RefundJpaEntity entity) {
        entity.changeMutableState(
                refund.getStatus(),
                refund.getRejectReason(),
                refund.getDecidedAt(),
                refund.getCompletedAt(),
                refund.getActiveOrderItemId(),
                refund.getShippingFeeAmount(),
                refund.getRemoteAreaFeeAmount(),
                refund.getDeductedShippingFeeAmount(),
                refund.getFailedStep(),
                refund.getFailureCode());
    }
}
