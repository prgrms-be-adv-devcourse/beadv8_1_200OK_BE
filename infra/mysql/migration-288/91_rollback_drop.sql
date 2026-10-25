-- 모듈별 스키마 이전 되돌리기 2단계(#288): 비어 있는 모듈 스키마를 지우고 권한을 거둔다. migrate.sh가 스키마가 비었음을 확인한 뒤 실행한다.
DROP DATABASE `paldogotgan_member`;
DROP DATABASE `paldogotgan_wallet`;
DROP DATABASE `paldogotgan_order`;
DROP DATABASE `paldogotgan_product`;
DROP DATABASE `paldogotgan_delivery`;
DROP DATABASE `paldogotgan_payout`;
DROP DATABASE `paldogotgan_media`;
DROP DATABASE `paldogotgan_refund`;
REVOKE ALL PRIVILEGES ON `paldogotgan\_%`.* FROM '__APP_USER__'@'%';
