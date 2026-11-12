-- 실패 사유 이름 변경 ORDER_LOCKED → ORDER_HELD (#391)
-- 실패 사유는 enum 이름 그대로(EnumType.STRING) 저장되므로, 옛 이름이 남아 있으면 JPA가 읽을 때 enum으로 바꾸지 못해 예외가 남
-- payout_failure는 INSERT 전용 표지만, 이 UPDATE는 값의 뜻이 아니라 이름만 바꾸는 예외적인 조치라 함께 둠
--   (사유 뜻은 같음: 같은 주문의 다른 주문상품 때문에 함께 보류된 후보. 잠그지 않는 사유라 잠금 동작도 바뀌지 않음)
-- 되돌리려면 반대 UPDATE(ORDER_HELD → ORDER_LOCKED)를 코드 되돌리기와 함께 적용해야 함 (코드와 데이터 이름이 다르면 읽기 실패)
UPDATE payout_failure
SET reason = 'ORDER_HELD'
WHERE reason = 'ORDER_LOCKED';
