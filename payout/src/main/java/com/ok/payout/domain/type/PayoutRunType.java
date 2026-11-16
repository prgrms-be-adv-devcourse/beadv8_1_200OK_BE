package com.ok.payout.domain.type;

// 정산 실행이 어느 경로로 시작됐는지 구분한다. 스냅샷에 남겨 나중에 어떤 실행인지 알 수 있게 한다 (#485)
// 배치 입구(adapter.in.batch)와 엔티티가 함께 쓰므로 domain에 두되, 엔티티를 두는 domain 바로 아래가 아니라 domain.type에 둔다.
// 배치 입구가 domain 중 PayoutPolicy와 이 패키지만 알 수 있게 하려는 것이다. 웹·이벤트 입구는 이 패키지도 쓰지 않는다.
public enum PayoutRunType {

    // 새벽 2시 정기 스케줄러가 시작한 실행
    SCHEDULED,

    // 관리자 실행 API가 시작한 실행
    MANUAL,

    // 대기 건너뛰기 API가 시작한 실행
    SKIP_WAIT
}
