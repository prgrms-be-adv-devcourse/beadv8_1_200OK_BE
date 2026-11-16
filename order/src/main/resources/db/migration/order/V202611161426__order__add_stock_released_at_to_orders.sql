-- 재고 해제를 확인한 시각(#657). 취소·결제 실패 주문인데 비어 있으면 정리 스케줄러가 재고 해제를 다시 부른다
-- 해제 실패를 따로 등록하지 않고 주문 상태와 이 값으로 대상을 다시 고르므로, 등록이 실패해 대상이 빠지는 일이 없다
ALTER TABLE orders
    ADD COLUMN stock_released_at DATETIME(6) NULL COMMENT '재고 해제를 확인한 시각, NULL이면 아직 확인 못 함' AFTER payment_requested_at,
    ADD INDEX idx_orders_status_stock_released_at (status, stock_released_at);

-- 이미 취소·결제 실패된 주문은 해제가 성공했는지 알 수 없어 비워 둔다. 상품의 해제는 여러 번 불러도 결과가 같아(#605)
-- 첫 재처리들이 한 번씩 다시 해제하고, 그때 지난 해제 실패분도 함께 정리된다
