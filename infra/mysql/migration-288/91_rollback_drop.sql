-- 모듈별 스키마 이전 되돌리기 2단계(#288): 앱 계정 권한을 거두고 비어 있는 모듈 스키마를 지운다. migrate.sh가 스키마가 비었음을 확인한 뒤 실행한다.
REVOKE ALL PRIVILEGES ON `paldogotgan_member`.* FROM '__APP_USER__'@'%';
REVOKE ALL PRIVILEGES ON `paldogotgan_wallet`.* FROM '__APP_USER__'@'%';
REVOKE ALL PRIVILEGES ON `paldogotgan_order`.* FROM '__APP_USER__'@'%';
REVOKE ALL PRIVILEGES ON `paldogotgan_product`.* FROM '__APP_USER__'@'%';
REVOKE ALL PRIVILEGES ON `paldogotgan_delivery`.* FROM '__APP_USER__'@'%';
REVOKE ALL PRIVILEGES ON `paldogotgan_payout`.* FROM '__APP_USER__'@'%';
REVOKE ALL PRIVILEGES ON `paldogotgan_media`.* FROM '__APP_USER__'@'%';
REVOKE ALL PRIVILEGES ON `paldogotgan_refund`.* FROM '__APP_USER__'@'%';
DROP DATABASE IF EXISTS `paldogotgan_member`;
DROP DATABASE IF EXISTS `paldogotgan_wallet`;
DROP DATABASE IF EXISTS `paldogotgan_order`;
DROP DATABASE IF EXISTS `paldogotgan_product`;
DROP DATABASE IF EXISTS `paldogotgan_delivery`;
DROP DATABASE IF EXISTS `paldogotgan_payout`;
DROP DATABASE IF EXISTS `paldogotgan_media`;
DROP DATABASE IF EXISTS `paldogotgan_refund`;
