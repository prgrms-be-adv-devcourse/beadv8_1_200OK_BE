package com.ok.refund.application.port.in;

/**
 * 사람이 봐야 하는 처리 중 환불을 찾아 알리는 입력 포트다(#577 멈춘 환불 찾기).
 * 정해진 시간마다 도는 알람(adapter.in.scheduler)이 부른다.
 */
public interface FindRefundsNeedingAttentionUseCase {

    /**
     * 멈춘 단계 줄이 있음, 단계 줄이 없음, 앞 단계는 성공했는데 다음 줄이 없음, 지갑 결과 대기가 오래됨 중 하나인 처리 중 환불을 찾아 알린다.
     *
     * @return 찾은 환불 수
     */
    int findAndReport();
}
