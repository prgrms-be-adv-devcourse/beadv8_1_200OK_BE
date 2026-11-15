package com.ok.payout.application.port.in;

/**
 * 정산·지갑 대사 입력 포트 (#619, 상위 #308).
 * 정산이 매일 지갑에 "이 정산들, 입금됐나요?"를 묻고, 지갑의 답으로 정산마다 일치·다시 보냄·관리자 확인 필요를 판정한다.
 */
public interface PayoutDepositCheckUseCase {

    /**
     * 매일 대사를 한 번 한다.
     * 3번을 다 물었는데 답이 끝내 오지 않은 줄을 먼저 관리자 확인 필요로 넘기고, 나머지 대기 줄을 500건씩 묶어 지갑에 묻는다.
     * Kafka로 보내기에 실패하면 그 묶음과 남은 줄은 묻지 않고(물은 횟수에도 넣지 않음) 멈춘다.
     */
    PayoutDepositCheckRunResult runCheck();

    /**
     * 지갑의 답 묶음 하나를 반영한다.
     * 가장 최근 질문의 처음 온 답만 반영하고, "없음"이고 물을 횟수가 남은 정산은 같은 트랜잭션에서 outbox 줄을 다시 보낼 것으로 되돌린다.
     * 입금 메시지는 짧은 간격 재발송이 보낸다(#620).
     */
    void handleAnswer(PayoutDepositCheckAnswerCommand command);
}
