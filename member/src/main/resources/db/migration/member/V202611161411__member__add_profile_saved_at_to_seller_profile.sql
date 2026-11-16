-- 판매자 프로필 수정 저장 시각 (#651): 저장할 때마다 출고지 우편번호를 보내므로, 바뀐 칸이 없는 저장도 UPDATE와 버전 검사를 거쳐야
-- 두 저장이 겹칠 때 나중 쪽이 읽어 둔 옛 값을 다시 보내지 않는다. 수정마다 이 칸을 새 시각으로 바꿔 항상 UPDATE가 일어나게 한다.
-- 등록만 하고 수정한 적 없는 프로필은 값이 없다(NULL). 등록·승인 때 보낸 메시지의 시각은 여기 남지 않는다
ALTER TABLE seller_profile
    ADD COLUMN profile_saved_at DATETIME(6) NULL COMMENT '판매자가 프로필 수정을 마지막으로 저장한 시각. 그 수정 때 보낸 출고지 메시지의 발생 시각과 같다' AFTER profile_image_key;
