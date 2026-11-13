---
issue: 544
module: refund
summary: 지갑 반환 결과 메시지를 받아 지갑이 돌려준 뒤에만 재고 복구·완료하고, 실패·불일치는 처리 중에 둔 채 실패로 기록한다
status: 확정
date: 2026-11-13
paths: [refund/src/main/java/com/ok/refund/application/service/HandleWalletRefundResultService.java, refund/src/main/java/com/ok/refund/adapter/in/event/RefundWalletKafkaListener.java, refund/src/main/java/com/ok/refund/adapter/in/event/RefundKafkaConsumerConfig.java, refund/src/main/java/com/ok/refund/application/service/DecideRefundService.java, refund/CLAUDE.md]
related: [512, 543, 545, 546, 388]
---

> 이 문서는 #544 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

환불은 승인 뒤 지갑에 반환 요청을 Kafka로 보내기만 했고, 결과를 받지 않았다. 그래서 "Kafka에 들어감"을 지갑 성공으로 보고 승인 요청 안에서 재고 복구와 완료까지 갔다(임시 기준). 지갑이 실패해도 완료로 남을 수 있었다.
지갑 팀이 11/12에 결과 메시지 모양을 제안했다. 지갑 쪽 발행 코드는 아직 없지만(지갑 #34) 그 모양으로 받는다고 가정하고 진행했다(사용자 결정).
범위: 결과 받기, 승인 흐름 나누기, 받은 메시지 거르기, 오류 처리. 범위 밖: 멈춘 환불 찾기·다시 시도(#545), 되돌리기(#546), DLT(#388).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 메시지 모양 | 지갑 제안 그대로: `wallet.wallet-refund-completed` / `wallet.wallet-refund-failed`, 키 orderId, 필드 refundId·orderId·buyerId·amount(+reason), 타입 헤더 없음 | 보내는 메시지와 같은 키라 같은 주문 순서가 지켜지고, 지갑에 바꿔 달라고 할 것이 없다 (사용자 결정) |
| 승인 응답 | 돌려줄 돈이 있으면 지갑 발행 뒤 `PROCESSING`, 0원은 요청 안에서 `COMPLETED` | 완료는 지갑이 돌려준 뒤여야 한다. 지갑 결과를 요청 안에서 기다리면 Kafka로 나눈 의미가 없다 (사용자 결정) |
| 지갑 실패 메시지 | 상태는 처리 중 그대로, `failedStep=WALLET`, `failureCode=지갑 사유`, ERROR 로그 | 실패 칸이 이미 있고 새 상태를 만들면 집계·조회·문서를 모두 손봐야 한다. 주문은 이미 취소를 기록해 거절로 바꾸면 어긋난다. 마무리는 #546 (사용자 결정) |
| 금액·주문이 다른 완료 메시지 | 완료하지 않고 `RESULT_MISMATCH`로 기록 | 지갑이 실제로 얼마를 돌려줬는지 믿을 수 없어 사람이 보게 남긴다 (사용자 결정) |
| 거르는 위치 | 리스너는 값만, 상태·금액은 서비스가 환불 행을 잠근 뒤 | 입력 어댑터는 DB를 볼 수 없다. 잠근 뒤 봐야 그사이 바뀐 상태도 막는다. 모듈 규칙 5절을 이에 맞춰 고쳤다 |
| 걸러 넘기는 메시지 | 예외 없이 로그만 남기고 넘김 | 예외를 던지면 오류 처리기가 다시 시도해 뒤 메시지가 기다린다 |
| 오류 처리 | 환불 전용 컨테이너 팩토리: `IllegalArgumentException`은 다시 시도 안 함, 그 밖은 1·2·4·8·16초 5번 | 기본 처리기는 간격 없이 바로 다시 시도해 잠깐의 장애도 못 버틴다. 정산(`PayoutKafkaConsumerConfig`)과 같은 규칙 (사용자 결정) |
| 지갑 완료 뒤 재고 복구 | 실패 기록 틀(`RefundStepRunner`)로 불러 끝까지 실패하면 `PRODUCT` 기록 | 멈춘 환불을 #545가 찾을 수 있게. 승인 요청 안의 호출은 여전히 #516 전까지 틀 없이 부른다 |

## 버린 대안

- **새 상태 FAILED:** 지갑 실패를 별도 상태로 두는 방법은 상태가 늘어 배송비 집계·목록·문서를 모두 고쳐야 하고, 주문 취소와 어긋난다. 감수하는 것은 지갑 실패 환불이 구매자에게 "처리 중"으로 보인다는 점.
- **다시 시도용 별도 토픽(@RetryableTopic):** 실패한 메시지만 옆 줄로 빼 뒷 메시지를 막지 않는 방법. 토픽이 늘고 팀 공통 DLT(#388)와 겹쳐 이번에는 쓰지 않았다. 감수하는 것은 재고 서버가 멈추면 지갑 결과 처리 줄이 메시지마다 최대 약 1분 기다린다는 점 (사용자 결정).
- **승인 요청이 지갑 결과를 기다림:** 응답은 `COMPLETED`로 유지되지만 요청이 지갑 처리 시간만큼 붙잡히고 시간 초과가 난다.

## 건드릴 때 주의

- 지갑은 같은 refundId에 처음 결과를 다시 보낼 수 있다. 완료 + 이미 완료는 정상(INFO), 실패 + 이미 완료는 지갑 결과가 어긋난 것(ERROR)이다.
- 리스너가 `refundKafkaListenerContainerFactory`를 써야 오류 처리 규칙이 걸린다. 기본 팩토리로 바뀌어도 동작은 하므로 통합 테스트(오류 처리기 연결)로 지킨다.
- 주문 번호 비교는 값 비교(`equals`)여야 한다. 테스트는 Long 캐시 밖 번호(1000, 6000)로 지킨다.
- DB가 다시 시도 시간(약 31초)보다 오래 멈추면 메시지가 ERROR 로그만 남기고 넘어가 처리 중으로 남는다. #545의 "오래 머문 환불 찾기"가 잡고, 보관은 DLT(#388).
- 완료돼도 전에 남은 `failedStep`은 지우지 않는다(이력). 멈춘 환불은 처리 중인 것만 본다.
