package com.ok.refund.application.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

// 서비스 단위 테스트에서 TransactionTemplate 구간이 콜백만 실행하게 하는 가짜 트랜잭션 매니저.
// Mockito mock으로 getTransaction을 stub하면, 구간을 열기 전에 끝나는 테스트(주문 호출 실패 등)에서 쓰지 않은 stub이
// 엄격 모드(MockitoExtension)에 걸려 테스트마다 lenient를 붙여야 한다. 아무것도 하지 않는 구현 하나를 함께 쓰는 편이 짧고 의도가 분명하다.
// 트랜잭션 동기화를 켜지 않으므로 TransactionSynchronizationManager에는 열린 트랜잭션이 없는 것으로 보인다
final class NoOpTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
    }

    @Override
    public void rollback(TransactionStatus status) {
    }
}
