package com.ok.member.seller.event;

import com.ok.common.event.DomainEvent;
import com.ok.common.event.SellerApprovedEvent;
import com.ok.member.seller.service.SellerEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 판매자 하위 도메인의 이벤트를 스프링 이벤트로 발행한다.
 *
 * <p>서비스가 넘긴 값으로 모듈 간 이벤트({@link DomainEvent})를 만들어 발행한다.
 * 발행은 호출한 서비스의 트랜잭션 안에서 일어나고, 구독하는 모듈은 커밋 후에 처리한다({@code @ApplicationModuleListener}).
 * 회원의 {@code SpringMemberEventPublisher}와 빈 이름이 겹치지 않도록 하위 도메인 이름을 붙인다.
 */
@Component
@RequiredArgsConstructor
public class SpringSellerEventPublisher implements SellerEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishSellerApproved(Long memberId, LocalDateTime approvedAt) {
        publish(new SellerApprovedEvent(memberId, approvedAt));
    }

    // 모듈 간 계약으로 정한 이벤트만 나가도록 DomainEvent만 받는다
    private void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

}
