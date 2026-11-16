package com.ok.order.order.domain;

// 실패 기록의 처리 상태. 지금은 사람이 원인을 확인하고 처리한 뒤 해결로 바꾼다(자동 재처리 없음, #666)
public enum OrderMessageFailureStatus {
    PENDING,  // 대기: 아직 처리하지 않음
    RESOLVED  // 해결: 다시 처리했거나 처리하지 않아도 되는 것으로 확인함
}
