---
issue: 496
module: delivery
summary: 택배사 중간 상태는 내부 이벤트로 발행만 하고, 조회는 저장된 출고 시각 기준 기간으로 멈추며, 멈춘 건은 ERROR 로그와 이벤트로 알린다
status: 확정
date: 2026-11-10
paths: [delivery/src/main/java/com/ok/delivery/adapter/out/carrier/TrackerCarrierClient.java, delivery/src/main/java/com/ok/delivery/adapter/in/event/CarrierEventListener.java, delivery/src/main/java/com/ok/delivery/adapter/in/event/CarrierStatusReportedEvent.java, delivery/src/main/java/com/ok/delivery/adapter/in/event/CarrierTrackingGaveUpEvent.java, delivery/src/main/java/com/ok/delivery/adapter/out/carrier/TrackerProperties.java, docs/design/order-flow-tracking.md]
related: [491, 495]
---

> 이 문서는 #496 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

#491의 조회 어댑터는 `DELIVERED`만 쓰고 나머지 상태(`IN_TRANSIT` 등)는 버리며, 조회에 상한이 없어 미발급 송장이나 오류가 계속되는 송장도 끝없이 조회한다. 이 작업은 중간 상태를 어떻게 다룰지, 조회를 언제 멈출지, 멈춘 건을 어떻게 알릴지를 정한다.

범위 밖으로 둔 것은 셋이다. 상태 이력을 실제로 저장하는 수신처(#378 사건 로그, #383 flowwatch)는 아직 없어 이후 이슈에서 붙인다. 무료 플랜의 호출 제한은 확인하지 못해 조회 간격 기본값(5분)을 바꾸지 않았다. 웹훅은 #495에서 다룬다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 중간 상태 | 배송 모듈 내부 이벤트 `CarrierStatusReportedEvent`로 발행만 한다. 같은 키(`status.code:time`)는 한 번만 발행하고 지금 구독자는 INFO 로그뿐이다 | (사용자 결정) 이력을 받을 수신처가 없고 설계 문서도 이력 테이블 분리를 확정 전이라고 한다. 이벤트로 두면 수신처가 생길 때 구독만 붙이면 된다 |
| 조회 상한 기준 | 저장된 출고 시각부터 기간(`max-tracking-period`, 기본 7일)이 지나면 멈춘다 | (사용자 결정) 미발급 송장이 항상 `NOT_FOUND`로 오지 않아 오류 종류로는 정할 수 없다. 출고 시각은 DB 값이라 재시작해도 기간이 초기화되지 않는다. 7일은 설계의 배송 중 기대 시간(2일)보다 넉넉하게 잡은 값이다 |
| 멈춘 건 알림 | `CarrierTrackingGaveUpEvent`를 발행하고 `CarrierEventListener`가 ERROR 로그를 남긴다 | (사용자 결정) DB를 바꾸지 않고 알 수 있게 한다. 멈춤 감지(#384)가 이 이벤트를 받아 이어 붙일 수 있다 |
| 포기 알림 횟수 | `gaveUp.add`로 한 번만 통과시키고, 발행이 실패하면 되돌려 다음 주기에 다시 알린다 | 요청 스레드, 기동 복구, 스케줄러가 같은 shipment를 동시에 포기 처리할 수 있다 |
| 시각이 빈 상태의 키 | `status.code:-`로 둔다 | 설계의 해시(상태 코드, 설명, 장소)는 설명과 장소 필드가 스키마에서 확인되지 않아 쓰지 않고, 설계 문서를 현재 동작에 맞춰 고쳤다 |
| 출고 시각 전달 | 추적 요청(`CarrierClient`와 그 호출 경로)에 `shippedAt`을 추가한다 | 기간을 메모리가 아니라 저장된 출고 시각으로 판정하려면 조회 클라이언트가 이 값을 받아야 한다 |

## 버린 대안

- **상한을 조회 횟수로:** 횟수는 메모리에만 있어 재시작할 때마다 0부터 다시 센다.
- **상한을 오류 종류로(`NOT_FOUND` 등):** 임의 번호가 실제 송장과 겹쳐 진행 중이나 `DELIVERED`가 오기도 하고, 택배사에 따라 `NOT_FOUND`나 `BAD_REQUEST`가 와서 구분되지 않는다.
- **이력 테이블을 DB에 만든다:** 설계가 확정 전인 구조를 먼저 정하게 되고 범위가 커진다.
- **상태 이력을 수신처가 생길 때까지 미룬다:** 이 이슈의 첫 완료 조건(상태가 이력으로 남고 한 번만 남는다)을 채우지 못한다.
- **멈춤을 shipment 상태 컬럼에 저장한다:** 엔티티 변경과 마이그레이션이 필요하고 범위가 커진다. 감수하는 것은 포기한 건이 DB에서 `SHIPPING`으로 남는다는 점이다.

## 건드릴 때 주의

- 조회, 마지막 발행 키, 포기 여부는 메모리에만 있다. 재시작하면 같은 마지막 상태가 한 번 더 발행될 수 있어, 수신처가 eventKey로 중복을 무시하는 것이 전제다(설계 §6.2).
- 포기 상태를 저장하지 않아, 상한을 넘은 `SHIPPING` 건은 재시작할 때마다 ERROR 로그와 포기 이벤트가 반복된다. 배포마다 그 건수만큼 쌓이므로 운영 알림 피로도는 따로 판단한다.
- 시각이 비는 서로 다른 사건은 같은 키로 합쳐진다. 설명과 장소 필드가 스키마에 있는지 확인되면 해시로 바꿀지 정한다.
- (미확인) `time` 필드는 기존 테스트의 응답 모양에서 확인했을 뿐 새 쿼리를 실제 Delivery Tracker에 호출해 본 적은 없다.
- (미확인) 무료 플랜의 호출 제한을 확인하지 못했다.
- (미확인) `shippedAt`이 `null`인 `SHIPPING` shipment가 있으면 상한 판정에서 NPE가 난다. 출고가 상태와 시각을 함께 세팅해 정상 경로에서는 생기지 않지만 DB 컬럼은 `NULL`을 허용하고, 실행해서 확인하지는 않았다.
- 기동 복구에서 한 건이 예외를 던지면 나머지 복구가 막힐 수 있다(#371의 범위).
- 기본 택배사(Mock)는 이 상태 이벤트와 상한을 쓰지 않는다. Mock으로 띄우는 `verify-delivery` 스킬로는 이 경로가 검증되지 않고 단위 테스트(`MockRestServiceServer`)로만 덮여 있다.
- `ArchitectureTest`에 `TrackerCarrierClient`가 새 이벤트 둘을 쓰는 것을 허용하는 예외를 두었다. 기존 `CarrierDeliveredEvent` 예외와 같은 이유(out 어댑터가 in이 소유한 이벤트를 발행)다.
