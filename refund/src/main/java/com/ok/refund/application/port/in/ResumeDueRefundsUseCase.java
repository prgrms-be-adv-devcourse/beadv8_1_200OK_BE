package com.ok.refund.application.port.in;

/**
 * 다시 볼 시각이 지난 승인 뒤 단계를 찾아 환불마다 이어 가는 입력 포트다(#576 다시 진행 알람).
 * 정해진 시간마다 도는 알람(adapter.in.scheduler)이 부른다.
 */
public interface ResumeDueRefundsUseCase {

    /**
     * 다시 볼 시각이 지난 환불을 한 묶음 찾아 하나씩 이어 간다. 환불 하나가 실패해도 나머지는 계속한다.
     * 한 회차의 시간 한도를 넘으면 남은 환불은 다음 회차로 넘긴다.
     *
     * @return 이어 가려고 시도한 환불 수(실패 포함, 시간 한도로 넘긴 환불은 빼고)
     */
    int resumeDue();
}
