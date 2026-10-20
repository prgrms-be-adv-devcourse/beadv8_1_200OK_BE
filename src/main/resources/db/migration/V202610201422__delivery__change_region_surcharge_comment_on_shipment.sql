-- 배송지 변경 승인 시 안내 금액을 더하므로 region_surcharge는 결제 시점 값이 아니라 받았거나 안내한 추가 운임 누적액이다 (#199)
-- 다음 배송지 변경 요청은 이 값과의 차액만 안내한다. 타입과 제약은 그대로 두고 설명만 바꾼다
ALTER TABLE shipment
    MODIFY COLUMN region_surcharge INT NOT NULL COMMENT '받았거나 안내한 추가 운임 누적액 (결제 시 추가 운임 + 승인된 배송지 변경의 안내 금액)';
